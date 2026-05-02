package com.codeauto.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.config.ConfigLoader;
import com.codeauto.context.CompactService;
import com.codeauto.context.TokenEstimator;
import com.codeauto.core.AgentLoop;
import com.codeauto.core.AgentLoopListener;
import com.codeauto.core.ChatMessage;
import com.codeauto.instructions.InstructionLoader;
import com.codeauto.model.AnthropicModelAdapter;
import com.codeauto.model.ModelAdapter;
import com.codeauto.model.MockModelAdapter;
import com.codeauto.mcp.McpService;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.session.SessionStore;
import com.codeauto.skills.SkillService;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolRegistry;
import com.codeauto.tools.DefaultTools;
import com.codeauto.tui.TuiApp;
import java.io.Console;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;

@CommandLine.Command(name = "codeauto", mixinStandardHelpOptions = true,
    description = "CodeAuto terminal coding assistant",
    subcommands = {McpCommand.class, SkillsCommand.class})
public class CodeAutoCli implements Runnable {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @CommandLine.Option(names = "--tui", description = "Start the full-screen terminal UI")
  boolean tui;

  @CommandLine.Option(names = "--mock", description = "Use the offline mock model")
  boolean mock;

  @CommandLine.Option(names = "--model", description = "Model name (overrides config and env)")
  String modelOverride;

  @CommandLine.Option(names = "--cwd", description = "Working directory (default: current dir)")
  String cwdOverride;

  @CommandLine.Option(names = "--max-steps", defaultValue = "32", description = "Maximum model/tool steps per turn")
  int maxSteps;

  @CommandLine.Option(names = "--max-tokens", description = "Max output tokens (overrides config)")
  Integer maxTokensOverride;

  @CommandLine.Option(names = "--resume", arity = "0..1", fallbackValue = "__latest__",
      description = "Resume the latest session, or resume a specific session id")
  String resumeTarget;

  @CommandLine.Option(names = "--fork", description = "Fork a session id and resume the fork")
  String forkTarget;

  private boolean consoleStreamedThisTurn;

  public static void main(String[] args) {
    System.setProperty("org.jline.terminal.disableDeprecatedProviderWarning", "true");
    int exit = new CommandLine(new CodeAutoCli()).execute(args);
    System.exit(exit);
  }

  @Override
  public void run() {
    Path cwd = resolveCwd();
    var runtime = new ConfigLoader().load(cwd);
    runtime = ConfigLoader.applyCliOverrides(runtime,
        new ConfigLoader.CliOverrides(modelOverride, maxTokensOverride == null ? 0 : maxTokensOverride));
    PermissionManager permissions = new PermissionManager(cwd);
    ToolRegistry tools = DefaultTools.create();
    tools.addTools(new McpService(new com.codeauto.manage.ManagementStore(), cwd).createBackedTools());
    ModelAdapter model = mock || "mock".equalsIgnoreCase(runtime.model())
        ? new MockModelAdapter()
        : new AnthropicModelAdapter(runtime, tools);
    if (tui) {
      new TuiApp(tools, model, cwd, maxSteps, runtime).run();
      return;
    }
    AgentLoop loop
        = new AgentLoop(model, tools, new ToolContext(cwd, permissions), maxSteps, consoleListener(), 200_000);
    String sessionId = UUID.randomUUID().toString().substring(0, 8);
    int savedCount = 1;
    SessionStore sessions = new SessionStore(cwd);
    try {
      sessions.cleanupExpiredSessions(Duration.ofDays(30));
    } catch (Exception ignored) {
      // Session cleanup is best-effort and should never block startup.
    }
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage(systemPrompt(cwd, permissions)));

    try {
      if (forkTarget != null && !forkTarget.isBlank()) {
        List<ChatMessage> loaded = sessions.load(forkTarget.trim());
        if (loaded.isEmpty()) {
          System.out.println("Session not found or empty: " + forkTarget);
        } else {
          String source = forkTarget.trim();
          sessionId = UUID.randomUUID().toString().substring(0, 8);
          messages.addAll(loaded);
          if (saveSession(sessions, sessionId, messages, 1)) {
            renameSession(sessions, sessionId, source + "_fork");
            savedCount = messages.size();
          }
          System.out.println("Forked session " + source + " into " + sessionId + ".");
        }
      } else if (resumeTarget != null) {
        String target = resolveResumeTarget(sessions, resumeTarget);
        if (target == null) {
          System.out.println("No saved sessions found.");
        } else {
          List<ChatMessage> loaded = sessions.load(target);
          if (loaded.isEmpty()) {
            System.out.println("Session not found or empty: " + target);
          } else {
            sessionId = target;
            messages.addAll(loaded);
            savedCount = messages.size();
            System.out.println("Resumed session " + sessionId + " with " + loaded.size() + " messages.");
          }
        }
      }
    } catch (Exception error) {
      throw new CommandLine.ExecutionException(new CommandLine(this), error.getMessage(), error);
    }

    System.out.println("CodeAuto (" + runtime.model() + "). Type /help, /tools, /status, or /exit.");
    try (CliInput cliInput = openCliInput()) {
      while (true) {
        String line = cliInput.readLine("> ");
        if (line == null) break;
        String input = line.trim();
        if (input.isBlank()) continue;
        if ("/exit".equals(input)) break;
        if ("/help".equals(input)) {
          System.out.println("""
              /help                Show commands
              /tools               List tools
              /skills              List discovered skills
              /sessions            List saved sessions
              /projects            List projects with saved sessions
              /mcp                 List configured MCP servers
              /memory              List memories, or add/delete with arguments
              /status              Show workspace, session, and context stats
              /model               Show active model
              /new                 Start a new session
              /resume <id>         Load a session
              /fork                Save current transcript into a new session
              /rename <name>       Rename current session metadata
              /compact             Compact middle conversation messages
              /config-paths        Show config home
              /permissions         Show permission storage and rule counts
              /exit                Exit
              """);
          continue;
        }
        if ("/tools".equals(input)) {
          tools.list().forEach(tool -> System.out.println(tool.name() + ": " + tool.description()));
          continue;
        }
        if ("/skills".equals(input)) {
          var skills = new SkillService(cwd).discover();
          if (skills.isEmpty()) {
            System.out.println("(none)");
          } else {
            skills.forEach(skill -> System.out.println(skill.name() + ": " + skill.skillFile()));
          }
          continue;
        }
        if ("/sessions".equals(input)) {
          try {
            var summaries = sessions.list();
            if (summaries.isEmpty()) {
              System.out.println("(none)");
            } else {
              summaries.forEach(summary ->
                  System.out.println(summary.id() + "  " + summary.title() + "  " + summary.updatedAt()));
            }
          } catch (Exception error) {
            System.out.println("Warning: could not list sessions: " + error.getMessage());
          }
          continue;
        }
        if ("/projects".equals(input)) {
          try {
            var projects = SessionStore.listAllProjects();
            if (projects.isEmpty()) {
              System.out.println("(none)");
            } else {
              projects.forEach(project ->
                  System.out.println(project.cwd() + "  sessions=" + project.sessionCount()
                      + "  updated=" + project.latestUpdatedAt()));
            }
          } catch (Exception error) {
            System.out.println("Warning: could not list projects: " + error.getMessage());
          }
          continue;
        }
        if ("/mcp".equals(input)) {
          var store = new com.codeauto.manage.ManagementStore();
          System.out.println(store.listObject(store.readMcp()));
          var mcpTools = new McpService(store, cwd).listTools();
          if (!mcpTools.isEmpty()) {
            System.out.println("tools:");
            mcpTools.forEach(tool -> System.out.println(tool.serverName() + "/" + tool.name() + ": " + tool.description()));
          }
          continue;
        }
        if (input.equals("/memory") || input.startsWith("/memory ")) {
          System.out.println(runMemoryCommand(input, tools, cwd, permissions));
          continue;
        }
        if ("/model".equals(input)) {
          System.out.println(runtime.model());
          continue;
        }
        if ("/status".equals(input)) {
          var stats = TokenEstimator.compute(messages, 200_000);
          System.out.println("cwd=" + cwd + ", session=" + sessionId + ", tools=" + tools.list().size()
              + ", ctx=" + stats.estimatedTokens() + " est tokens, level=" + stats.warningLevel());
          continue;
        }
        if ("/compact".equals(input)) {
          int before = messages.size();
          var result = CompactService.compactWithStats(messages, 8);
          messages = new ArrayList<>(result.messages());
          if (result.summary() != null) {
            if (appendCompactBoundary(sessions, sessionId, result.summary(), result.tokensBefore(), result.tokensAfter())) {
              savedCount = messages.size();
            } else {
              savedCount = Math.min(savedCount, messages.size());
            }
          } else {
            savedCount = Math.min(savedCount, messages.size());
          }
          System.out.println("Compacted messages: " + before + " -> " + messages.size());
          continue;
        }
        if ("/new".equals(input)) {
          sessionId = UUID.randomUUID().toString().substring(0, 8);
          messages = new ArrayList<>();
          messages.add(new ChatMessage.SystemMessage(systemPrompt(cwd, permissions)));
          savedCount = 1;
          System.out.println("Started session " + sessionId);
          continue;
        }
        if (input.startsWith("/resume ")) {
          String target = input.substring("/resume ".length()).trim();
          List<ChatMessage> loaded = sessions.load(target);
          if (loaded.isEmpty()) {
            System.out.println("Session not found or empty: " + target);
          } else {
            sessionId = target;
            messages = new ArrayList<>();
            messages.add(new ChatMessage.SystemMessage(systemPrompt(cwd, permissions)));
            messages.addAll(loaded);
            savedCount = messages.size();
            System.out.println("Resumed session " + sessionId + " with " + loaded.size() + " messages.");
          }
          continue;
        }
        if ("/fork".equals(input)) {
          sessionId = UUID.randomUUID().toString().substring(0, 8);
          if (saveSession(sessions, sessionId, messages, 1)) {
            savedCount = messages.size();
          }
          System.out.println("Forked current transcript into session " + sessionId);
          continue;
        }
        if (input.startsWith("/rename ")) {
          String title = input.substring("/rename ".length()).trim();
          if (title.isBlank()) {
            System.out.println("Usage: /rename <name>");
          } else {
            if (renameSession(sessions, sessionId, title)) {
              System.out.println("Renamed session " + sessionId + " to " + title);
            }
          }
          continue;
        }
        if ("/config-paths".equals(input)) {
          System.out.println("home=" + com.codeauto.config.RuntimeConfig.homeDir());
          continue;
        }
        if ("/permissions".equals(input)) {
          System.out.println(permissions.describePermissions());
          continue;
        }
        permissions.beginTurn();
        messages.add(new ChatMessage.UserMessage(input));
        consoleStreamedThisTurn = false;
        messages = new ArrayList<>(loop.runTurn(messages));
        permissions.endTurn();
        if (saveSession(sessions, sessionId, messages, savedCount)) {
          savedCount = messages.size();
        }
        if (!consoleStreamedThisTurn) {
          messages.stream()
              .filter(ChatMessage.AssistantMessage.class::isInstance)
              .map(ChatMessage.AssistantMessage.class::cast)
              .reduce((first, second) -> second)
              .ifPresent(message -> System.out.println(message.content()));
        }
      }
    } catch (Exception error) {
      throw new CommandLine.ExecutionException(new CommandLine(this), error.getMessage(), error);
    }
  }

  private String resolveResumeTarget(SessionStore sessions, String requested) {
    if (!"__latest__".equals(requested)) {
      return requested.trim();
    }
    try {
      var summaries = sessions.list();
      return summaries.isEmpty() ? null : summaries.getFirst().id();
    } catch (Exception error) {
      return null;
    }
  }

  private boolean saveSession(
      SessionStore sessions,
      String sessionId,
      List<ChatMessage> messages,
      int alreadySavedCount
  ) {
    try {
      sessions.save(sessionId, messages, alreadySavedCount);
      return true;
    } catch (Exception error) {
      System.out.println("Warning: could not save session " + sessionId + ": " + error.getMessage());
      return false;
    }
  }

  private boolean renameSession(SessionStore sessions, String sessionId, String title) {
    try {
      sessions.rename(sessionId, title);
      return true;
    } catch (Exception error) {
      System.out.println("Warning: could not rename session " + sessionId + ": " + error.getMessage());
      return false;
    }
  }

  private boolean appendCompactBoundary(
      SessionStore sessions,
      String sessionId,
      ChatMessage.ContextSummaryMessage summary,
      int preTokens,
      int postTokens
  ) {
    try {
      sessions.appendCompactBoundary(sessionId, summary, "manual", preTokens, postTokens);
      return true;
    } catch (Exception error) {
      System.out.println("Warning: could not save compact boundary for session "
          + sessionId + ": " + error.getMessage());
      return false;
    }
  }

  private Path resolveCwd() {
    if (cwdOverride != null && !cwdOverride.isBlank()) {
      Path custom = Path.of(cwdOverride).toAbsolutePath().normalize();
      if (Files.isDirectory(custom)) return custom;
      System.out.println("Warning: --cwd directory not found, using current directory: " + custom);
    }
    Path current = Path.of("").toAbsolutePath().normalize();
    return projectRootForBundledBin(current);
  }

  public static Path projectRootForBundledBin(Path current) {
    if (current == null) return Path.of("").toAbsolutePath().normalize();
    Path normalized = current.toAbsolutePath().normalize();
    Path fileName = normalized.getFileName();
    Path parent = normalized.getParent();
    if (fileName != null
        && "bin".equalsIgnoreCase(fileName.toString())
        && parent != null
        && Files.isRegularFile(parent.resolve("pom.xml"))
        && Files.isDirectory(parent.resolve("src").resolve("main").resolve("java").resolve("com").resolve("codeauto"))) {
      return parent.toAbsolutePath().normalize();
    }
    return normalized;
  }

  private String systemPrompt(Path cwd, PermissionManager permissions) {
    return InstructionLoader.systemPrompt(cwd, permissions.summary());
  }

  public static Charset stdinCharset() {
    Charset explicit = charset(System.getProperty("codeauto.cli.charset"));
    if (explicit != null) return explicit;
    explicit = charset(System.getenv("CODEAUTO_CLI_CHARSET"));
    if (explicit != null) return explicit;
    Console console = System.console();
    if (console != null) return console.charset();
    explicit = charset(System.getProperty("native.encoding"));
    return explicit == null ? Charset.defaultCharset() : explicit;
  }

  private static Charset charset(String name) {
    if (name == null || name.isBlank()) return null;
    try {
      return Charset.forName(name.trim());
    } catch (Exception ignored) {
      return null;
    }
  }

  static CliInput openCliInput() {
    try {
      Terminal terminal = TerminalBuilder.builder()
          .system(true)
          .build();
      LineReader reader = LineReaderBuilder.builder()
          .terminal(terminal)
          .build();
      return new JLineCliInput(reader, terminal);
    } catch (Exception ignored) {
      return new ScannerCliInput(new Scanner(System.in, stdinCharset()));
    }
  }

  interface CliInput extends AutoCloseable {
    String readLine(String prompt);

    @Override
    void close() throws Exception;
  }

  private record JLineCliInput(LineReader reader, Terminal terminal) implements CliInput {
    @Override
    public String readLine(String prompt) {
      try {
        return reader.readLine(prompt);
      } catch (EndOfFileException ignored) {
        return null;
      } catch (UserInterruptException ignored) {
        return "/exit";
      }
    }

    @Override
    public void close() throws Exception {
      terminal.close();
    }
  }

  private record ScannerCliInput(Scanner scanner) implements CliInput {
    @Override
    public String readLine(String prompt) {
      System.out.print(prompt);
      return scanner.hasNextLine() ? scanner.nextLine() : null;
    }

    @Override
    public void close() {
      scanner.close();
    }
  }

  private String runMemoryCommand(String input, ToolRegistry tools, Path cwd, PermissionManager permissions) {
    String rest = input.equals("/memory") ? "list" : input.substring("/memory ".length()).trim();
    String toolName;
    var json = MAPPER.createObjectNode();
    if (rest.equals("list") || rest.startsWith("list ")) {
      toolName = "list_memory";
      String query = rest.length() > 4 ? rest.substring(4).trim() : "";
      if (!query.isBlank()) json.put("query", query);
    } else if (rest.startsWith("add ")) {
      toolName = "save_memory";
      String[] parts = splitMemoryPayload(rest.substring("add ".length()).trim(), 3);
      if (parts.length < 3) return "Usage: /memory add <type>::<title>::<content>";
      json.put("type", parts[0]);
      json.put("title", parts[1]);
      json.put("content", parts[2]);
    } else if (rest.startsWith("delete ")) {
      toolName = "delete_memory";
      json.put("id", rest.substring("delete ".length()).trim());
    } else {
      return "Usage: /memory list [query] | /memory add <type>::<title>::<content> | /memory delete <id>";
    }
    var result = tools.execute(toolName, json, new ToolContext(cwd, permissions));
    return result.output();
  }

  private static String[] splitMemoryPayload(String payload, int limit) {
    String[] parts = payload.split("::", limit);
    for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
    return parts;
  }

  private AgentLoopListener consoleListener() {
    return new AgentLoopListener() {
      @Override
      public void onAutoCompact(CompactService.CompactResult result) {
        System.out.println("[compact] summarized " + result.removedCount()
            + " messages (" + result.tokensBefore() + " -> " + result.tokensAfter() + " est tokens)");
      }

      @Override
      public void onProgressMessage(String content) {
        if (content != null && !content.isBlank()) {
          System.out.println("[progress] " + content);
        }
      }

      @Override
      public void onAssistantDelta(String delta) {
        if (delta != null && !delta.isEmpty()) {
          consoleStreamedThisTurn = true;
          System.out.print(delta);
        }
      }

      @Override
      public void onAssistantMessage(String content) {
        if (consoleStreamedThisTurn) {
          System.out.println();
        }
      }

      @Override
      public void onToolStart(String toolName, com.fasterxml.jackson.databind.JsonNode input) {
        System.out.println("[tool] " + toolName + " started");
      }

      @Override
      public void onToolResult(String toolName, String output, boolean isError) {
        String status = isError ? "failed" : "completed";
        int chars = output == null ? 0 : output.length();
        System.out.println("[tool] " + toolName + " " + status + " (" + chars + " chars)");
      }
    };
  }
}
