package com.codeauto.cli;

import com.codeauto.config.ConfigLoader;
import com.codeauto.context.CompactService;
import com.codeauto.context.TokenEstimator;
import com.codeauto.core.AgentLoop;
import com.codeauto.core.AgentLoopListener;
import com.codeauto.core.ChatMessage;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import picocli.CommandLine;

@CommandLine.Command(name = "codeauto", mixinStandardHelpOptions = true,
    description = "CodeAuto terminal coding assistant",
    subcommands = {McpCommand.class, SkillsCommand.class})
public class CodeAutoCli implements Runnable {
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

  public static void main(String[] args) {
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
    messages.add(new ChatMessage.SystemMessage("You are CodeAuto. Permissions: " + permissions.summary()));

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
    try (Scanner scanner = new Scanner(System.in)) {
      while (true) {
        System.out.print("> ");
        if (!scanner.hasNextLine()) break;
        String input = scanner.nextLine().trim();
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
              /status              Show workspace, session, and context stats
              /model               Show active model
              /new                 Start a new session
              /resume <id>         Load a session
              /fork                Save current transcript into a new session
              /rename <name>       Rename current session metadata
              /compact             Compact middle conversation messages
              /config-paths        Show config home
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
          messages.add(new ChatMessage.SystemMessage("You are CodeAuto. Permissions: " + permissions.summary()));
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
            messages.add(new ChatMessage.SystemMessage("You are CodeAuto. Permissions: " + permissions.summary()));
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
        permissions.beginTurn();
        messages.add(new ChatMessage.UserMessage(input));
        messages = new ArrayList<>(loop.runTurn(messages));
        permissions.endTurn();
        if (saveSession(sessions, sessionId, messages, savedCount)) {
          savedCount = messages.size();
        }
        messages.stream()
            .filter(ChatMessage.AssistantMessage.class::isInstance)
            .map(ChatMessage.AssistantMessage.class::cast)
            .reduce((first, second) -> second)
            .ifPresent(message -> System.out.println(message.content()));
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
    return Path.of("").toAbsolutePath().normalize();
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
