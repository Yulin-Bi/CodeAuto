package com.codeauto.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.config.ConfigLoader;
import com.codeauto.config.RuntimeConfig;
import com.codeauto.context.CompactService;
import com.codeauto.reflection.ReflectionService;
import com.codeauto.context.ContextStats;
import com.codeauto.context.TokenEstimator;
import com.codeauto.core.AgentLoop;
import com.codeauto.core.AgentLoopListener;
import com.codeauto.core.ChatMessage;
import com.codeauto.instructions.InstructionLoader;
import com.codeauto.manage.ManagementStore;
import com.codeauto.mcp.McpService;
import com.codeauto.model.AnthropicModelAdapter;
import com.codeauto.model.ModelAdapter;
import com.codeauto.model.MockModelAdapter;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.permissions.PermissionPrompt;
import com.codeauto.permissions.PermissionRequest;
import com.codeauto.permissions.PermissionResponse;
import com.codeauto.session.SessionStore;
import com.codeauto.skills.SkillService;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolRegistry;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class TuiApp {

  private static final int CONTEXT_WINDOW = 200_000;
  private static final int PERMISSION_TIMEOUT_SECS = 120;

  static final int SCROLL_STEP = 5;
  static final int SLASH_MENU_MAX_ROWS = 7;
  static final int LIVE_PROGRESS_MAX_LINES = 5;

  static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};

  private final ToolRegistry tools;
  private ModelAdapter model;
  private final Path cwd;
  private final int maxSteps;
  private RuntimeConfig config;

  private Terminal terminal;
  private Writer writer;
  private AgentLoop loop;
  private SessionStore sessions;
  private PermissionManager permissions;
  private final List<ChatMessage> messages = new ArrayList<>();
  private String sessionId;
  private int savedCount;

  private final List<TranscriptEntry> transcript = new ArrayList<>();
  int nextEntryId = 1;

  int slashMenuSelectedIndex;
  volatile SessionPickerState sessionPicker;
  volatile boolean approvalFeedbackMode;
  final StringBuilder approvalFeedbackInput = new StringBuilder();
  String compactNotification;
  private Path historyFile;
  String input = "";
  int cursorPos;
  volatile boolean isBusy;
  volatile long busyStartedAtMillis;
  volatile CompletableFuture<?> agentFuture;
  String statusText;
  final Deque<ToolStatus> recentTools = new ArrayDeque<>();
  String runningToolName;
  private ContextStats contextStats;
  private final List<String> history = new ArrayList<>();
  private int historyIndex;
  private String historyDraft = "";
  volatile PendingApproval pendingApproval;
  volatile boolean running = true;
  Integer streamingAssistantEntryId;
  final StringBuilder streamingAssistantBuffer = new StringBuilder();
  final List<String> turnProgressTrace = new ArrayList<>();
  Integer progressTraceEntryId;
  final Set<Integer> expandedProgressEntries = new HashSet<>();
  volatile boolean cursorBlinkVisible = true;
  private ScheduledExecutorService cursorBlinker;

  int transcriptScrollOffset;
  boolean transcriptAutoScroll = true;

  volatile String statusLineText = "";
  volatile int spinnerFrame;

  int skillCount = -1;
  int mcpToolCount = -1;

  int lastTermWidth = -1;
  int lastTermHeight = -1;

  List<String> cachedRenderLines;
  boolean transcriptDirty = true;

  // --- Delegates ---
  private TuiRenderer renderer;

  // --- Records ---

  record ToolStatus(String name, boolean isError) {}
  record PendingApproval(PermissionRequest request, CompletableFuture<PermissionResponse> future,
                         int selectedIndex) {}
  record SessionPickerState(
      List<SessionStore.SessionSummary> sessions,
      int selectedIndex,
      int deleteConfirmIndex,
      boolean allProjects,
      List<SessionStore.ProjectMeta> projects,
      int projectIndex,
      String browseStorageName
  ) {}

  // --- Accessors for TuiRenderer and TuiCommands ---

  ToolRegistry tools() { return tools; }
  String modelName() { return config != null ? config.model() : "unknown"; }
  Path cwd() { return cwd; }
  SessionStore sessions() { return sessions; }
  PermissionManager permissions() { return permissions; }
  List<ChatMessage> messages() { return messages; }
  String sessionId() { return sessionId; }
  ContextStats contextStats() { return contextStats; }
  int skillCount() { return skillCount; }
  int mcpToolCount() { return mcpToolCount; }
  int messageCount() { return messages.size(); }
  int toolCount() { return tools.list().size(); }
  boolean isBusy() { return isBusy; }
  int spinnerFrame() { return spinnerFrame; }
  String statusText() { return statusText; }
  String inputText() { return input; }
  int cursorPos() { return cursorPos; }
  boolean cursorBlinkVisible() { return cursorBlinkVisible; }
  boolean transcriptAutoScroll() { return transcriptAutoScroll; }
  int transcriptScrollOffset() { return transcriptScrollOffset; }
  Integer progressTraceEntryId() { return progressTraceEntryId; }
  boolean approvalFeedbackMode() { return approvalFeedbackMode; }
  String approvalFeedbackText() { return approvalFeedbackInput.toString(); }
  PendingApproval pendingApproval() { return pendingApproval; }
  SessionPickerState sessionPicker() { return sessionPicker; }
  String compactNotification() { return compactNotification; }
  boolean hasPinnedProgress() { return progressTraceEntryId != null && !turnProgressTrace.isEmpty(); }
  boolean isTranscriptDirty() { return transcriptDirty; }
  List<String> cachedRenderLines() { return cachedRenderLines; }
  int slashMenuSelectedIndex() { return slashMenuSelectedIndex; }
  boolean expandedProgressContains(int id) { return expandedProgressEntries.contains(id); }

  String turnProgressTraceSnapshot() {
    synchronized (turnProgressTrace) {
      if (turnProgressTrace.isEmpty()) return null;
      return String.join("\n", new ArrayList<>(turnProgressTrace));
    }
  }

  int nextEntryId() { return nextEntryId++; }
  void setRunning(boolean v) { running = v; }
  void setTranscriptScrollOffset(int v) { transcriptScrollOffset = v; }
  void setTranscriptAutoScroll(boolean v) { transcriptAutoScroll = v; }
  void setCachedRenderLines(List<String> lines) { cachedRenderLines = lines; }
  void setTranscriptDirty(boolean v) { transcriptDirty = v; }

  void addRecentTool(String name, boolean isError) {
    recentTools.addLast(new ToolStatus(name, isError));
    if (recentTools.size() > 10) recentTools.removeFirst();
  }

  String statusWithElapsed() {
    if (!isBusy || busyStartedAtMillis <= 0) return statusText;
    long elapsed = Math.max(0, System.currentTimeMillis() - busyStartedAtMillis);
    long seconds = elapsed / 1000;
    if (seconds < 3) return statusText;
    return statusText + " (" + formatElapsed(seconds) + ")";
  }

  private static String formatElapsed(long seconds) {
    long minutes = seconds / 60;
    long rest = seconds % 60;
    return minutes > 0 ? minutes + "m " + rest + "s" : rest + "s";
  }

  // --- Public API ---

  public TuiApp(ToolRegistry tools, ModelAdapter model, Path cwd, int maxSteps,
                RuntimeConfig config) {
    this.tools = tools;
    this.model = model;
    this.cwd = cwd;
    this.maxSteps = maxSteps;
    this.config = config;
  }

  public void run() {
    try {
      terminal = TerminalBuilder.builder()
          .system(true)
          .build();
      writer = terminal.writer();

      renderer = new TuiRenderer(terminal, writer);

      permissions = new PermissionManager(cwd, new com.codeauto.permissions.PermissionStore(),
          new PermissionPrompt() {
            @Override
            public PermissionDecision ask(PermissionRequest req) {
              return askPermission(req).decision();
            }
            @Override
            public PermissionResponse askDetailed(PermissionRequest req) {
              return askPermission(req);
            }
          });
      loop = new AgentLoop(model, tools, new ToolContext(cwd, permissions), maxSteps, listener, CONTEXT_WINDOW);
      sessions = new SessionStore(cwd);
      sessionId = UUID.randomUUID().toString().substring(0, 8);
      savedCount = 1;
      messages.add(new ChatMessage.SystemMessage(systemPrompt()));

      historyFile = RuntimeConfig.homeDir().resolve("history.jsonl");
      try {
        if (java.nio.file.Files.exists(historyFile)) {
          var lines = java.nio.file.Files.readAllLines(historyFile, java.nio.charset.StandardCharsets.UTF_8);
          for (var line : lines) {
            if (!line.isBlank()) history.add(line.trim());
          }
        }
      } catch (Exception ignored) {}
      historyIndex = history.size();

      try {
        sessions.cleanupExpiredSessions(Duration.ofDays(30));
      } catch (Exception ignored) {}

      try {
        var summaries = sessions.list();
        if (!summaries.isEmpty()) {
          addEntry(new TranscriptEntry.Assistant(nextEntryId++,
              "Found " + summaries.size() + " saved session(s). Type /resume to continue one."));
        }
      } catch (Exception ignored) {}

      try {
        skillCount = new SkillService(cwd).discover().size();
      } catch (Exception ignored) {}
      try {
        var store = new ManagementStore();
        mcpToolCount = new McpService(store, cwd).listTools().size();
      } catch (Exception ignored) {}

      terminal.enterRawMode();
      writer.write(Ansi.ENTER_ALT);
      writer.write(Ansi.HIDE_CURSOR);
      writer.write(Ansi.ENABLE_SGR_MOUSE);
      writer.flush();

      terminal.handle(Terminal.Signal.INT, signal -> {
        if (isBusy) {
          cancelAgent();
        } else {
          running = false;
        }
      });
      terminal.handle(Terminal.Signal.WINCH, signal -> handleResize());
      handleResize();
      startCursorBlinker();
      render();
      eventLoop();

    } catch (Exception e) {
      System.err.println("TUI error: " + e.getMessage());
      e.printStackTrace();
    } finally {
      cleanup();
    }
  }

  private void cleanup() {
    try {
      if (cursorBlinker != null) {
        cursorBlinker.shutdownNow();
        cursorBlinker = null;
      }
      writer.write(Ansi.SHOW_CURSOR);
      writer.write(Ansi.DISABLE_SGR_MOUSE);
      writer.write(Ansi.EXIT_ALT);
      writer.write("\nSession " + sessionId + " saved. To resume: codeauto --resume " + sessionId + "\n");
      writer.flush();
      if (terminal != null) terminal.close();
    } catch (Exception ignored) {}
  }

  // --- Transcript helpers ---

  void addEntry(TranscriptEntry entry) {
    synchronized (transcript) {
      transcript.add(entry);
    }
    transcriptDirty = true;
  }

  private void setEntry(int index, TranscriptEntry entry) {
    synchronized (transcript) {
      transcript.set(index, entry);
    }
    transcriptDirty = true;
  }

  void clearEntries() {
    synchronized (transcript) {
      transcript.clear();
    }
    transcriptDirty = true;
  }

  int transcriptSize() {
    synchronized (transcript) {
      return transcript.size();
    }
  }

  List<TranscriptEntry> transcriptSnapshot() {
    synchronized (transcript) {
      return new ArrayList<>(transcript);
    }
  }

  // --- Cursor blink ---

  private void startCursorBlinker() {
    cursorBlinker = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "codeauto-cursor-blink");
      thread.setDaemon(true);
      return thread;
    });
    cursorBlinker.scheduleAtFixedRate(() -> {
      if (!running || terminal == null || writer == null) return;
      if (sessionPicker != null || pendingApproval != null) return;
      if (isBusy) {
        spinnerFrame = (spinnerFrame + 1) % SPINNER_FRAMES.length;
        updateStatusLine();
        render();
      } else {
        cursorBlinkVisible = !cursorBlinkVisible;
        render();
      }
    }, 500, 500, TimeUnit.MILLISECONDS);
  }

  // --- Event loop ---

  private void eventLoop() throws IOException {
    while (running) {
      int c = terminal.reader().read();
      if (c < 0) break;

      handleResize();

      if (pendingApproval != null) {
        handleApprovalKey(c);
        continue;
      }

      if (sessionPicker != null) {
        handleSessionPickerKey(c);
        continue;
      }

      if (isBusy) {
        if (c == 0x03) {
          cancelAgent();
          continue;
        }
        if (c == 0x0F) {
          toggleLastProgressExpanded();
          continue;
        }
        if (c == 0x1B) {
          compactNotification = null;
          handleEscapeSequence(TuiInputParser.readEscapeSequence(c, terminal));
        }
        continue;
      }

      switch (c) {
        case 0x03 -> { running = false; return; }
        case 0x0D, 0x0A -> {
          compactNotification = null;
          var cmds = getVisibleCommands();
          if (!cmds.isEmpty()) {
            int idx = Math.min(slashMenuSelectedIndex, cmds.size() - 1);
            if (!input.equals(cmds.get(idx).usage())) {
              fillSlashCommand(cmds.get(idx));
              break;
            }
          }
          submitInput();
        }
        case 0x7F, 0x08 -> handleBackspace();
        case 0x09 -> handleTab();
        case 0x1B -> {
          compactNotification = null;
          handleEscapeSequence(TuiInputParser.readEscapeSequence(c, terminal));
        }
        case 0x15 -> { input = ""; cursorPos = 0; render(); }
        case 0x01 -> {
          if (input.isEmpty()) { scrollToTop(); }
          else { cursorPos = 0; render(); }
        }
        case 0x05 -> {
          if (input.isEmpty()) { scrollToBottom(); }
          else { cursorPos = input.length(); render(); }
        }
        case 0x10 -> { historyUp(); render(); }
        case 0x0E -> { historyDown(); render(); }
        case 0x0F -> toggleLastProgressExpanded();
        default -> {
          if (c >= 0x20) {
            compactNotification = null;
            slashMenuSelectedIndex = 0;
            insertText(String.valueOf(Character.toChars(c)));
          }
        }
      }
    }
  }

  // --- Input handling ---

  private void handleBackspace() {
    if (cursorPos <= 0 || input.isEmpty()) return;
    if (cursorPos >= input.length()) {
      input = input.substring(0, input.length() - 1);
    } else {
      input = input.substring(0, cursorPos - 1) + input.substring(cursorPos);
    }
    cursorPos--;
    render();
  }

  void handleResize() {
    if (terminal == null) return;
    int cols = terminal.getSize().getColumns();
    int rows = terminal.getSize().getRows();
    if (cols == lastTermWidth && rows == lastTermHeight) return;
    lastTermWidth = cols;
    lastTermHeight = rows;
    render();
  }

  private void handleTab() {
    if (input.isEmpty()) {
      input = "/";
      cursorPos = 1;
    } else {
      var cmds = getVisibleCommands();
      if (!cmds.isEmpty()) {
        int idx = Math.max(0, Math.min(slashMenuSelectedIndex, cmds.size() - 1));
        fillSlashCommand(cmds.get(idx));
        return;
      }
    }
    render();
  }

  private void handleEscapeSequence(String seq) {
    if (seq.equals("\033[A") || seq.equals("\033OA")) {
      var cmds = getVisibleCommands();
      if (!cmds.isEmpty()) {
        slashMenuSelectedIndex = Math.max(0, slashMenuSelectedIndex - 1);
        render();
      } else {
        historyUp();
        render();
      }
    } else if (seq.equals("\033[B") || seq.equals("\033OB")) {
      var cmds = getVisibleCommands();
      if (!cmds.isEmpty()) {
        slashMenuSelectedIndex = Math.min(cmds.size() - 1, slashMenuSelectedIndex + 1);
        render();
      } else {
        historyDown();
        render();
      }
    } else if (seq.equals("\033[C") || seq.equals("\033OC")) {
      if (cursorPos < input.length()) { cursorPos++; render(); }
    } else if (seq.equals("\033[D") || seq.equals("\033OD")) {
      if (cursorPos > 0) { cursorPos--; render(); }
    } else if (seq.equals("\033[H") || seq.equals("\033[1~")) {
      cursorPos = 0; render();
    } else if (seq.equals("\033[F") || seq.equals("\033[4~")) {
      cursorPos = input.length(); render();
    } else if (seq.equals("\033[3~")) {
      if (cursorPos < input.length()) {
        input = input.substring(0, cursorPos) + input.substring(cursorPos + 1);
        render();
      }
    } else if (seq.equals("\033[5~")) {
      scrollTranscript(-SCROLL_STEP);
    } else if (seq.equals("\033[6~")) {
      scrollTranscript(SCROLL_STEP);
    } else if (seq.equals("\033[1;3A") || seq.equals("\033[1;5A")) {
      scrollTranscript(-1);
    } else if (seq.equals("\033[1;3B") || seq.equals("\033[1;5B")) {
      scrollTranscript(1);
    } else {
      Integer scrollDelta = TuiInputParser.parseMouseScroll(seq);
      if (scrollDelta != null) {
        scrollTranscript(scrollDelta);
      } else if (seq.equals("\033")) {
        if (!input.isEmpty()) { input = ""; cursorPos = 0; render(); }
      }
    }
  }

  private void historyUp() {
    if (history.isEmpty() || historyIndex <= 0) return;
    if (historyIndex == history.size()) historyDraft = input;
    historyIndex--;
    input = history.get(historyIndex);
    cursorPos = input.length();
  }

  private void historyDown() {
    if (historyIndex >= history.size()) return;
    historyIndex++;
    input = historyIndex == history.size() ? historyDraft : history.get(historyIndex);
    cursorPos = input.length();
  }

  private void scrollTranscript(int delta) {
    if (delta < 0) {
      transcriptAutoScroll = false;
    }
    transcriptScrollOffset = Math.max(0, transcriptScrollOffset + delta);
    render();
  }

  private void scrollToTop() {
    transcriptAutoScroll = false;
    transcriptScrollOffset = 0;
    render();
  }

  private void scrollToBottom() {
    transcriptAutoScroll = true;
    render();
  }

  private void insertText(String text) {
    if (cursorPos >= input.length()) {
      input += text;
    } else {
      input = input.substring(0, cursorPos) + text + input.substring(cursorPos);
    }
    cursorPos += text.length();
    render();
  }

  // --- Slash menu ---

  List<TuiCommands.SlashCommand> getVisibleCommands() {
    if (!input.startsWith("/")) return List.of();
    if (input.equals("/")) return TuiCommands.SLASH_COMMANDS;
    var matches = new ArrayList<TuiCommands.SlashCommand>();
    for (var cmd : TuiCommands.SLASH_COMMANDS) {
      if (cmd.usage().startsWith(input)) {
        matches.add(cmd);
      }
    }
    return matches;
  }

  private void fillSlashCommand(TuiCommands.SlashCommand cmd) {
    input = cmd.usage();
    cursorPos = input.length();
    slashMenuSelectedIndex = 0;
    render();
  }

  // --- Submit input (slash command dispatch) ---

  private void submitInput() {
    var text = input.trim();
    if (text.isEmpty()) return;

    if (history.isEmpty() || !history.getLast().equals(text)) {
      history.add(text);
      if (historyFile != null) {
        try {
          java.nio.file.Files.writeString(historyFile, text + "\n",
              java.nio.charset.StandardCharsets.UTF_8,
              java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
      }
    }
    historyIndex = history.size();
    historyDraft = "";
    input = "";
    cursorPos = 0;

    addEntry(new TranscriptEntry.User(nextEntryId++, text));

    // Try slash command dispatch
    if (TuiCommands.execute(text, this)) {
      render();
      return;
    }

    // Submit to AgentLoop
    isBusy = true;
    busyStartedAtMillis = System.currentTimeMillis();
    progressTraceEntryId = null;
    synchronized (turnProgressTrace) {
      turnProgressTrace.clear();
    }
    statusText = "Thinking...";
    statusLineText = "Thinking...";
    updateStatusLine();
    render();

    messages.add(new ChatMessage.UserMessage(text));

    agentFuture = CompletableFuture.runAsync(() -> {
      try {
        permissions.beginTurn();
        var nextMessages = new ArrayList<>(loop.runTurn(messages));
        permissions.endTurn();
        messages.clear();
        messages.addAll(nextMessages);
        sessions.save(sessionId, messages, savedCount);
        savedCount = messages.size();
      } catch (Exception e) {
        if (loop.isCancelled()) {
          messages.add(new ChatMessage.AssistantMessage("(Interrupted)"));
          try {
            sessions.save(sessionId, messages, savedCount);
            savedCount = messages.size();
          } catch (Exception ignored) {}
        } else {
          addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Error: " + e.getMessage()));
        }
      } finally {
        clearStatusLine();
        materializeProgressTrace();
        isBusy = false;
        busyStartedAtMillis = 0;
        agentFuture = null;
        transcriptAutoScroll = true;
        statusText = null;
        contextStats = TokenEstimator.compute(messages, CONTEXT_WINDOW);
        render();
      }
    });
  }

  private void cancelAgent() {
    loop.cancel();
    var f = agentFuture;
    if (f != null) {
      f.cancel(true);
    }
    streamingAssistantEntryId = null;
    streamingAssistantBuffer.setLength(0);
    materializeProgressTrace();
    progressTraceEntryId = null;
    synchronized (turnProgressTrace) {
      turnProgressTrace.clear();
    }
    runningToolName = null;
    recentTools.clear();
    clearStatusLine();
    statusText = "Interrupted";
    statusLineText = null;
    addEntry(new TranscriptEntry.Assistant(nextEntryId++, "(Interrupted)"));
    render();
  }

  // --- Slash command implementations (called from TuiCommands) ---

  void switchModel(String modelName) {
    if (modelName == null || modelName.isBlank()) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Usage: /model <name>"));
      return;
    }
    config = config.withModel(modelName);
    model = "mock".equalsIgnoreCase(config.model())
        ? new MockModelAdapter()
        : new AnthropicModelAdapter(config, tools);
    loop = new AgentLoop(model, tools, new ToolContext(cwd, permissions), maxSteps, listener, CONTEXT_WINDOW);
    try {
      ConfigLoader.writeUserSettings(config);
      addEntry(new TranscriptEntry.Assistant(nextEntryId++,
          "Switched model to " + config.model() + " and saved to " + RuntimeConfig.homeDir().resolve("settings.json")));
    } catch (Exception error) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++,
          "Switched model to " + config.model() + ", but could not save settings: " + error.getMessage()));
    }
  }

  String systemPrompt() {
    return InstructionLoader.systemPrompt(cwd, permissions.summary());
  }

  String mcpStatus() {
    var service = new McpService(new ManagementStore(), cwd);
    var servers = service.configuredServers();
    if (servers.isEmpty()) {
      return "No MCP servers configured.";
    }
    var toolsByServer = new java.util.LinkedHashMap<String, List<String>>();
    var errorsByServer = new java.util.LinkedHashMap<String, String>();
    for (var tool : service.listTools()) {
      if ("(error)".equals(tool.name())) {
        errorsByServer.put(tool.serverName(), tool.description());
      } else {
        toolsByServer.computeIfAbsent(tool.serverName(), ignored -> new ArrayList<>()).add(tool.name());
      }
    }

    var sb = new StringBuilder();
    for (var server : servers) {
      var names = toolsByServer.getOrDefault(server.name(), List.of());
      String status = errorsByServer.containsKey(server.name()) ? "error" : "ok";
      String transport = server.isHttp() ? "http" : "stdio/" + server.protocol();
      sb.append(server.name())
          .append(" [").append(status).append("] ")
          .append(transport)
          .append(" tools=").append(names.size());
      if (errorsByServer.containsKey(server.name())) {
        sb.append("\n  error: ").append(errorsByServer.get(server.name()));
      } else if (!names.isEmpty()) {
        sb.append("\n  ").append(String.join(", ", names));
      }
      sb.append("\n");
    }
    return sb.toString().trim();
  }

  String permissionsSummary() {
    return permissions.describePermissions();
  }

  void newSession() {
    sessionId = UUID.randomUUID().toString().substring(0, 8);
    messages.clear();
    messages.add(new ChatMessage.SystemMessage(systemPrompt()));
    savedCount = 1;
    clearEntries();
    transcriptScrollOffset = 0;
    transcriptAutoScroll = true;
    addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Session cleared. Starting fresh."));
  }

  void forkSession() {
    var newId = UUID.randomUUID().toString().substring(0, 8);
    try {
      String forkName = null;
      try {
        var summaries = sessions.list();
        String currentTitle = "(untitled)";
        for (var s : summaries) {
          if (s.id().equals(sessionId)) {
            currentTitle = s.title();
            break;
          }
        }
        String base = currentTitle + "_fork";
        forkName = base;
        int counter = 1;
        boolean taken;
        do {
          taken = false;
          for (var s : summaries) {
            if (s.title().equals(forkName)) { taken = true; break; }
          }
          if (taken) { counter++; forkName = base + counter; }
        } while (taken);
      } catch (Exception ignored) {}
      sessions.save(newId, messages, 1);
      if (forkName != null) sessions.rename(newId, forkName);
      savedCount = messages.size();
      addEntry(new TranscriptEntry.Assistant(nextEntryId++,
          "Forked " + (forkName != null ? "as \"" + forkName + "\" " : "") + "into session " + newId));
    } catch (Exception e) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Fork failed: " + e.getMessage()));
    }
  }

  void renameSession(String title) {
    try {
      sessions.rename(sessionId, title);
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Renamed session to " + title));
    } catch (Exception e) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Rename failed: " + e.getMessage()));
    }
  }

  void openSessionPicker() {
    try {
      var summaries = sessions.list();
      if (summaries.isEmpty()) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "No saved sessions found."));
        render();
      } else {
        List<SessionStore.ProjectMeta> projects = List.of();
        try {
          projects = SessionStore.listAllProjects();
        } catch (Exception ignored) {}
        sessionPicker = new SessionPickerState(summaries, 0, -1, false, projects, 0, null);
        render();
      }
    } catch (Exception e) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Error listing sessions: " + e.getMessage()));
      render();
    }
  }

  void resumeSessionById(String target) {
    try {
      var loaded = sessions.load(target);
      if (loaded == null || loaded.isEmpty()) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Session not found: " + target));
      } else {
        sessionId = target;
        messages.clear();
        messages.add(new ChatMessage.SystemMessage(systemPrompt()));
        messages.addAll(loaded);
        savedCount = messages.size();
        clearEntries();
        transcriptScrollOffset = 0;
        transcriptAutoScroll = true;
        for (var entry : TranscriptEntry.fromMessages(messages)) {
          addEntry(entry);
        }
        addEntry(new TranscriptEntry.Assistant(nextEntryId++,
            "Session " + sessionId + " resumed (" + loaded.size() + " messages)."));
      }
    } catch (Exception e) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Resume failed: " + e.getMessage()));
    }
  }

  void runCompact() {
    if (messages.size() <= 2) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Not enough conversation to compress."));
      render();
      return;
    }
    isBusy = true;
    busyStartedAtMillis = System.currentTimeMillis();
    statusText = "Compressing...";
    render();

    agentFuture = CompletableFuture.runAsync(() -> {
      try {
        int before = messages.size();
        var result = CompactService.compactWithStats(messages, 8, 200_000, cwd, model);
        messages.clear();
        messages.addAll(result.messages());
        if (result.summary() != null) {
          sessions.appendCompactBoundary(sessionId, result.summary(), "manual",
              result.tokensBefore(), result.tokensAfter());
          savedCount = messages.size();
        } else {
          savedCount = Math.min(savedCount, messages.size());
        }
        var stats = result.tokensBefore() > 0
            ? "Compacted messages: " + before + " -> " + messages.size()
            : "Could not compress further.";
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, stats));
        if (result.tokensBefore() > 0) {
          int savedTokens = Math.max(0, result.tokensBefore() - result.tokensAfter());
          int savedPct = Math.max(1, (int) ((double) savedTokens / result.tokensBefore() * 100));
          compactNotification = "ctx -" + savedPct + "% (saved " + savedTokens + " tokens)";
        }
      } catch (Throwable e) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Compression failed: " + e.getMessage()));
      } finally {
        isBusy = false;
        busyStartedAtMillis = 0;
        agentFuture = null;
        statusText = null;
        render();
      }
    });
  }

  // --- AgentLoop listener ---

  private final AgentLoopListener listener = new AgentLoopListener() {
    @Override
    public void onContextStats(ContextStats stats) {
      contextStats = stats;
      render();
    }

    @Override
    public void onAutoCompact(CompactService.CompactResult result) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++,
          "Context auto-compressed: " + result.removedCount() + " messages summarized."));
      savedCount = result.messages() != null ? result.messages().size() - 1 : savedCount;
      if (result.tokensBefore() > 0) {
        int savedTokens = Math.max(0, result.tokensBefore() - result.tokensAfter());
        int savedPct = Math.max(1, (int) ((double) savedTokens / result.tokensBefore() * 100));
        compactNotification = "ctx -" + savedPct + "% (saved " + savedTokens + " tokens)";
      }
      render();
    }

    @Override
    public void onProgressMessage(String content) {
      if (content != null && !content.isBlank()) {
        statusText = content;
        recordProgressTrace(content);
        statusLineText = content;
        updateStatusLine();
        render();
      }
    }

    @Override
    public void onAssistantDelta(String delta) {
      if (delta == null || delta.isEmpty()) return;
      synchronized (transcript) {
        transcript.removeIf(e -> e instanceof TranscriptEntry.Status);
        materializeProgressTraceLocked();

        if (streamingAssistantEntryId == null) {
          streamingAssistantEntryId = nextEntryId++;
          streamingAssistantBuffer.setLength(0);
          transcript.add(new TranscriptEntry.Assistant(streamingAssistantEntryId, ""));
        }
        streamingAssistantBuffer.append(delta);
        for (int i = transcript.size() - 1; i >= 0; i--) {
          var entry = transcript.get(i);
          if (entry instanceof TranscriptEntry.Assistant a && a.id() == streamingAssistantEntryId) {
            transcript.set(i, new TranscriptEntry.Assistant(a.id(), streamingAssistantBuffer.toString()));
            break;
          }
        }
      }
      transcriptDirty = true;
      transcriptAutoScroll = true;
      render();
    }

    @Override
    public void onAssistantMessage(String content) {
      if (content != null && !content.isBlank()) {
        if (streamingAssistantEntryId != null) {
          synchronized (transcript) {
            transcript.removeIf(e -> e instanceof TranscriptEntry.Status);
            materializeProgressTraceLocked();

            for (int i = transcript.size() - 1; i >= 0; i--) {
              var entry = transcript.get(i);
              if (entry instanceof TranscriptEntry.Assistant a && a.id() == streamingAssistantEntryId) {
                transcript.set(i, new TranscriptEntry.Assistant(a.id(), content));
                break;
              }
            }
            streamingAssistantEntryId = null;
            streamingAssistantBuffer.setLength(0);
          }
          transcriptDirty = true;
          render();
          return;
        }
        clearStatusLine();
        materializeProgressTrace();
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, content));
        render();
      }
    }

    @Override
    public void onToolStart(String toolName, JsonNode input) {
      runningToolName = toolName;
      statusText = "Running " + toolName + "...";
      statusLineText = "Running " + toolName + "...";
      recordProgressTrace(TuiRenderer.PROGRESS_RUNNING, "Running " + toolName);
      updateStatusLine();
      render();
    }

    @Override
    public void onToolResult(String toolName, String output, boolean isError) {
      runningToolName = null;
      recentTools.addLast(new ToolStatus(toolName, isError));
      if (recentTools.size() > 10) recentTools.removeFirst();
      statusText = "Thinking...";
      statusLineText = "Processed " + toolName + " (" + recentTools.size() + " total)";
      completeProgressTrace(toolName, isError);
      updateStatusLine();
      transcriptDirty = true;
      render();
    }

    @Override
    public void onTurnComplete(List<ChatMessage> allMessages, int turnStartIndex) {
      var messages = List.copyOf(allMessages);
      CompletableFuture.runAsync(() -> {
        try {
          ReflectionService.reflectIfNeeded(messages, model, cwd, turnStartIndex).ifPresent(memory -> {
            addEntry(new TranscriptEntry.Assistant(nextEntryId++,
                "[reflection] saved: " + memory.title()));
          });
        } catch (Exception e) {
          System.err.println("[reflection] error: " + e.getMessage());
        }
      });
    }
  };

  // --- Progress trace ---

  private void recordProgressTrace(String line) {
    recordProgressTrace(TuiRenderer.PROGRESS_INFO, line);
  }

  private void recordProgressTrace(String state, String line) {
    if (line == null || line.isBlank()) return;
    synchronized (turnProgressTrace) {
      String cleaned = state + normalizeProgressLine(Ansi.stripAnsi(line));
      if (!turnProgressTrace.isEmpty() && turnProgressTrace.getLast().equals(cleaned)) return;
      turnProgressTrace.add(cleaned);
      if (turnProgressTrace.size() > 12) {
        turnProgressTrace.removeFirst();
      }
    }
  }

  private void completeProgressTrace(String toolName, boolean isError) {
    String runningLine = TuiRenderer.PROGRESS_RUNNING + "Running " + normalizeProgressLine(toolName);
    String completedLine = (isError ? TuiRenderer.PROGRESS_ERROR : TuiRenderer.PROGRESS_SUCCESS)
        + (isError ? "Failed " : "Processed ")
        + normalizeProgressLine(toolName);
    synchronized (turnProgressTrace) {
      for (int i = turnProgressTrace.size() - 1; i >= 0; i--) {
        if (turnProgressTrace.get(i).equals(runningLine)) {
          turnProgressTrace.set(i, completedLine);
          return;
        }
      }
    }
    recordProgressTrace(isError ? TuiRenderer.PROGRESS_ERROR : TuiRenderer.PROGRESS_SUCCESS,
        (isError ? "Failed " : "Processed ") + toolName);
  }

  private static String normalizeProgressLine(String line) {
    return line
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replaceAll("[\\t\\x0B\\f ]+", " ")
        .trim();
  }

  private void materializeProgressTrace() {
    synchronized (transcript) {
      materializeProgressTraceLocked();
    }
  }

  private void materializeProgressTraceLocked() {
    String body;
    synchronized (turnProgressTrace) {
      if (turnProgressTrace.isEmpty()) return;
      var lines = new ArrayList<>(turnProgressTrace);
      body = String.join("\n", lines);
    }
    if (progressTraceEntryId == null) {
      progressTraceEntryId = nextEntryId++;
      transcript.add(progressInsertIndexLocked(), new TranscriptEntry.Progress(progressTraceEntryId, body));
    } else {
      TranscriptEntry.Progress updated = null;
      for (int i = transcript.size() - 1; i >= 0; i--) {
        var entry = transcript.get(i);
        if (entry instanceof TranscriptEntry.Progress p && p.id() == progressTraceEntryId) {
          updated = new TranscriptEntry.Progress(p.id(), body);
          transcript.remove(i);
          break;
        }
      }
      if (updated != null) {
        transcript.add(progressInsertIndexLocked(), updated);
      } else {
        transcript.add(progressInsertIndexLocked(), new TranscriptEntry.Progress(progressTraceEntryId, body));
      }
    }
    transcriptDirty = true;
  }

  private int progressInsertIndexLocked() {
    Integer targetAssistantId = streamingAssistantEntryId;
    if (targetAssistantId == null) {
      for (int i = transcript.size() - 1; i >= 0; i--) {
        var entry = transcript.get(i);
        if (entry instanceof TranscriptEntry.Assistant a) {
          targetAssistantId = a.id();
          break;
        }
      }
    }
    if (targetAssistantId != null) {
      for (int i = 0; i < transcript.size(); i++) {
        var entry = transcript.get(i);
        if (entry instanceof TranscriptEntry.Assistant a && a.id() == targetAssistantId) {
          return i;
        }
      }
    }
    return transcript.size();
  }

  private void toggleLastProgressExpanded() {
    Integer id = null;
    synchronized (transcript) {
      for (int i = transcript.size() - 1; i >= 0; i--) {
        if (transcript.get(i) instanceof TranscriptEntry.Progress p) {
          id = p.id();
          break;
        }
      }
    }
    if (id == null) return;
    if (expandedProgressEntries.contains(id)) {
      expandedProgressEntries.remove(id);
    } else {
      expandedProgressEntries.add(id);
    }
    transcriptDirty = true;
    render();
  }

  // --- Status line ---

  private void updateStatusLine() {
    String text = statusLineText;
    if (text == null || text.isBlank()) return;
    materializeProgressTrace();
  }

  private void clearStatusLine() {
    synchronized (transcript) {
      if (transcript.removeIf(e -> e instanceof TranscriptEntry.Status)) {
        transcriptDirty = true;
      }
    }
  }

  // --- Permission prompt ---

  private PermissionResponse askPermission(PermissionRequest request) {
    var future = new CompletableFuture<PermissionResponse>();
    pendingApproval = new PendingApproval(request, future, 0);
    render();
    try {
      return future.get(PERMISSION_TIMEOUT_SECS, TimeUnit.SECONDS);
    } catch (Exception e) {
      return new PermissionResponse(PermissionDecision.DENY_ONCE);
    } finally {
      pendingApproval = null;
    }
  }

  private void handleApprovalKey(int c) throws IOException {
    var pa = pendingApproval;
    if (pa == null) return;
    var choices = pa.request().choices();

    if (approvalFeedbackMode) {
      switch (c) {
        case 0x03 -> { running = false; return; }
        case 0x0D, 0x0A -> {
          String fb = approvalFeedbackInput.toString().trim();
          var pa2 = pendingApproval;
          if (pa2 != null) {
            approvalFeedbackMode = false;
            pendingApproval = null;
            pa2.future().complete(new PermissionResponse(PermissionDecision.DENY_WITH_FEEDBACK, fb.isEmpty() ? null : fb));
          }
          render();
          return;
        }
        case 0x7F, 0x08 -> {
          if (approvalFeedbackInput.length() > 0) {
            approvalFeedbackInput.setLength(approvalFeedbackInput.length() - 1);
            render();
          }
          return;
        }
        case 0x1B -> {
          if (TuiInputParser.readEscapeSequence(c, terminal).equals("\033")) {
            approvalFeedbackMode = false;
            render();
          }
          return;
        }
        default -> {
          if (c >= 0x20) {
            approvalFeedbackInput.append(Character.toChars(c));
            render();
          }
        }
      }
      return;
    }

    switch (c) {
      case 0x03 -> { running = false; return; }
      case 'y', 'Y' -> {
        int yi = choices.indexOf(PermissionDecision.ALLOW_ONCE);
        if (yi >= 0) {
          compactNotification = null;
          pendingApproval = null;
          pa.future().complete(new PermissionResponse(PermissionDecision.ALLOW_ONCE));
          render();
        }
      }
      case 'n', 'N' -> {
        if (choices.contains(PermissionDecision.DENY_ONCE)) {
          compactNotification = null;
          pendingApproval = null;
          pa.future().complete(new PermissionResponse(PermissionDecision.DENY_ONCE));
          render();
        }
      }
      case '1', '2', '3', '4', '5', '6', '7' -> {
        int pIdx = c - '1';
        if (pIdx >= 0 && pIdx < choices.size()) {
          var pDecision = choices.get(pIdx);
          if (pDecision == PermissionDecision.DENY_WITH_FEEDBACK) {
            approvalFeedbackMode = true;
            approvalFeedbackInput.setLength(0);
            render();
          } else {
            compactNotification = null;
            pendingApproval = null;
            pa.future().complete(new PermissionResponse(pDecision));
            render();
          }
        }
      }
      case 0x1B -> {
        String es = TuiInputParser.readEscapeSequence(c, terminal);
        if (es.equals("\033[A") || es.equals("\033OA")) {
          pendingApproval = new PendingApproval(pa.request(), pa.future(),
              Math.max(0, pa.selectedIndex() - 1));
          render();
        } else if (es.equals("\033[B") || es.equals("\033OB")) {
          pendingApproval = new PendingApproval(pa.request(), pa.future(),
              Math.min(choices.size() - 1, pa.selectedIndex() + 1));
          render();
        } else if (es.equals("\033")) {
          pendingApproval = null;
          pa.future().complete(new PermissionResponse(PermissionDecision.DENY_ONCE));
          render();
        }
      }
      case 0x0D, 0x0A -> {
        if (pa.selectedIndex() >= 0 && pa.selectedIndex() < choices.size()) {
          var decision = choices.get(pa.selectedIndex());
          if (decision == PermissionDecision.DENY_WITH_FEEDBACK) {
            approvalFeedbackMode = true;
            approvalFeedbackInput.setLength(0);
            render();
          } else {
            pendingApproval = null;
            pa.future().complete(new PermissionResponse(decision));
            render();
          }
        }
      }
    }
  }

  // --- Session picker ---

  private void loadSessionFromPicker(String target) {
    try {
      var loaded = sessions.load(target);
      if (loaded == null || loaded.isEmpty()) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Session not found: " + target));
      } else {
        sessionId = target;
        messages.clear();
        messages.add(new ChatMessage.SystemMessage(systemPrompt()));
        messages.addAll(loaded);
        savedCount = messages.size();
        clearEntries();
        transcriptScrollOffset = 0;
        transcriptAutoScroll = true;
        for (var entry : TranscriptEntry.fromMessages(messages)) {
          addEntry(entry);
        }
        addEntry(new TranscriptEntry.Assistant(nextEntryId++,
            "Session " + sessionId + " resumed (" + loaded.size() + " messages)."));
      }
    } catch (Exception e) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Resume failed: " + e.getMessage()));
    }
  }

  private void loadSessionFromProject(String storageName, String target) {
    try {
      var loaded = SessionStore.loadSession(storageName, target);
      if (loaded == null || loaded.isEmpty()) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Session not found: " + target));
      } else {
        sessionId = target;
        messages.clear();
        messages.add(new ChatMessage.SystemMessage(systemPrompt()));
        messages.addAll(loaded);
        savedCount = messages.size();
        clearEntries();
        transcriptScrollOffset = 0;
        transcriptAutoScroll = true;
        for (var entry : TranscriptEntry.fromMessages(messages)) {
          addEntry(entry);
        }
        addEntry(new TranscriptEntry.Assistant(nextEntryId++,
            "Session " + sessionId + " resumed from other project (" + loaded.size() + " messages)."));
      }
    } catch (Exception e) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Resume failed: " + e.getMessage()));
    }
  }

  private void handleSessionPickerKey(int c) throws IOException {
    var sp = sessionPicker;
    if (sp == null) return;

    switch (c) {
      case 0x03 -> { running = false; return; }
      case 0x1B -> {
        String es = TuiInputParser.readEscapeSequence(c, terminal);
        if (es.equals("\033[A") || es.equals("\033OA")) {
          if (sp.allProjects()) {
            int idx = Math.max(0, sp.projectIndex() - 1);
            sessionPicker = new SessionPickerState(sp.sessions(), sp.selectedIndex(), -1, true, sp.projects(), idx, null);
          } else {
            int idx = Math.max(0, sp.selectedIndex() - 1);
            sessionPicker = new SessionPickerState(sp.sessions(), idx, -1, false, sp.projects(), sp.projectIndex(), null);
          }
          render();
        } else if (es.equals("\033[B") || es.equals("\033OB")) {
          if (sp.allProjects()) {
            int idx = Math.min(sp.projects().size() - 1, sp.projectIndex() + 1);
            sessionPicker = new SessionPickerState(sp.sessions(), sp.selectedIndex(), -1, true, sp.projects(), idx, null);
          } else {
            int idx = Math.min(sp.sessions().size() - 1, sp.selectedIndex() + 1);
            sessionPicker = new SessionPickerState(sp.sessions(), idx, -1, false, sp.projects(), sp.projectIndex(), null);
          }
          render();
        } else if (es.equals("\033")) {
          sessionPicker = null;
          render();
        }
      }
      case 0x0D, 0x0A -> {
        if (sp.allProjects()) {
          if (sp.projectIndex() >= 0 && sp.projectIndex() < sp.projects().size()) {
            var project = sp.projects().get(sp.projectIndex());
            String storageName = project.storageName();
            try {
              var projectSessions = SessionStore.listSessions(storageName);
              sessionPicker = new SessionPickerState(projectSessions, 0, -1, false,
                  sp.projects(), sp.projectIndex(), storageName);
            } catch (Exception e) {
              sessionPicker = null;
            }
            render();
          }
        } else {
          if (sp.selectedIndex() >= 0 && sp.selectedIndex() < sp.sessions().size()) {
            var session = sp.sessions().get(sp.selectedIndex());
            sessionPicker = null;
            if (sp.browseStorageName() != null) {
              loadSessionFromProject(sp.browseStorageName(), session.id());
            } else {
              loadSessionFromPicker(session.id());
            }
            render();
          }
        }
      }
      case 0x09 -> {
        if (sp.allProjects()) {
          sessionPicker = new SessionPickerState(sp.sessions(), sp.selectedIndex(), -1, false,
              sp.projects(), sp.projectIndex(), sp.browseStorageName());
        } else {
          sessionPicker = new SessionPickerState(sp.sessions(), 0, -1, true,
              sp.projects(), 0, sp.browseStorageName());
        }
        render();
      }
      case 'd', 'D' -> {
        if (sp.allProjects()) return;
        if (sp.deleteConfirmIndex() == sp.selectedIndex()) {
          if (sp.selectedIndex() >= 0 && sp.selectedIndex() < sp.sessions().size()) {
            var session = sp.sessions().get(sp.selectedIndex());
            try {
              deleteSessionFile(session.id());
            } catch (Exception ignored) {}
            try {
              var remaining = sessions.list();
              if (remaining.isEmpty()) {
                sessionPicker = null;
              } else {
                sessionPicker = new SessionPickerState(remaining, 0, -1, false, sp.projects(), sp.projectIndex(), null);
              }
            } catch (Exception e) {
              sessionPicker = null;
            }
            render();
          }
        } else {
          sessionPicker = new SessionPickerState(sp.sessions(), sp.selectedIndex(), sp.selectedIndex(), false, sp.projects(), sp.projectIndex(), null);
          render();
        }
      }
    }
  }

  private void deleteSessionFile(String sessionId) throws Exception {
    String projectName = cwd.toAbsolutePath().normalize().toString()
        .replaceAll("[/\\\\:]+", "-").replaceAll("^-+", "");
    Path file = RuntimeConfig.homeDir().resolve("projects")
        .resolve(projectName).resolve(sessionId + ".jsonl");
    java.nio.file.Files.deleteIfExists(file);
  }

  // --- Render ---

  private synchronized void render() {
    renderer.render(this);
  }
}
