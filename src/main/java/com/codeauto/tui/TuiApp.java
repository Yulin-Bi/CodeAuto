package com.codeauto.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.config.ConfigLoader;
import com.codeauto.config.RuntimeConfig;
import com.codeauto.context.CompactService;
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
import com.codeauto.todo.TodoStore;
import com.codeauto.background.BackgroundTaskRegistry;
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
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int CONTEXT_WINDOW = 200_000;
  private static final int PERMISSION_TIMEOUT_SECS = 120;
  private static final int SCROLL_STEP = 5;
  private static final int SLASH_MENU_MAX_ROWS = 7;
  private static final int LIVE_PROGRESS_MAX_LINES = 5;

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

  /** Transcript entries (thread-safe: always synchronize on this object when reading/writing). */
  private final List<TranscriptEntry> transcript = new ArrayList<>();

  private int nextEntryId = 1;
  private int slashMenuSelectedIndex;
  private volatile SessionPickerState sessionPicker;
  private volatile boolean approvalFeedbackMode;
  private final StringBuilder approvalFeedbackInput = new StringBuilder();
  private String compactNotification;
  private Path historyFile;
  private String input = "";
  private int cursorPos;
  private volatile boolean isBusy;
  private volatile long busyStartedAtMillis;
  private volatile CompletableFuture<?> agentFuture;
  private String statusText;
  private final Deque<ToolStatus> recentTools = new ArrayDeque<>();
  private String runningToolName;
  private ContextStats contextStats;
  private final List<String> history = new ArrayList<>();
  private int historyIndex;
  private String historyDraft = "";
  private volatile PendingApproval pendingApproval;
  private volatile boolean running = true;
  private Integer streamingAssistantEntryId;
  private final StringBuilder streamingAssistantBuffer = new StringBuilder();
  private final List<String> turnProgressTrace = new ArrayList<>();
  private Integer progressTraceEntryId;
  private final Set<Integer> expandedProgressEntries = new HashSet<>();
  private volatile boolean cursorBlinkVisible = true;
  private ScheduledExecutorService cursorBlinker;

  // Scrolling
  private int transcriptScrollOffset;
  private boolean transcriptAutoScroll = true;

  // Status line (spinner + text shown during AgentLoop execution)
  private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};
  private static final String PROGRESS_RUNNING = "RUNNING::";
  private static final String PROGRESS_SUCCESS = "SUCCESS::";
  private static final String PROGRESS_ERROR = "ERROR::";
  private static final String PROGRESS_INFO = "INFO::";
  private volatile String statusLineText = "";
  private volatile int spinnerFrame;

  // External service status
  private int skillCount = -1;
  private int mcpToolCount = -1;

  // Terminal size tracking for resize detection
  private int lastTermWidth = -1;
  private int lastTermHeight = -1;

  // Cached rendered transcript lines to avoid reprocessing on every render
  private List<String> cachedRenderLines;
  private boolean transcriptDirty = true;

  private record ToolStatus(String name, boolean isError) {}
  private record PendingApproval(PermissionRequest request, CompletableFuture<PermissionResponse> future,
                                 int selectedIndex) {}
  private record SlashCommand(String usage, String description) {}
  private static final List<SlashCommand> SLASH_COMMANDS = List.of(
      new SlashCommand("/help", "Show commands"),
      new SlashCommand("/tools", "List available tools"),
      new SlashCommand("/skills", "List discovered skills"),
      new SlashCommand("/sessions", "List saved sessions"),
      new SlashCommand("/status", "Show workspace, session, and context stats"),
      new SlashCommand("/model", "Show or switch active model"),
      new SlashCommand("/mcp", "Show MCP server and tool status"),
      new SlashCommand("/ls [path]", "List local files without model call"),
      new SlashCommand("/grep <pattern>::[path]", "Search local files without model call"),
      new SlashCommand("/read <path>", "Read local file without model call"),
      new SlashCommand("/write <path>::<content>", "Write local file with review"),
      new SlashCommand("/modify <path>::<content>", "Replace local file with review"),
      new SlashCommand("/edit <path>::<search>::<replace>", "Edit local file with review"),
      new SlashCommand("/patch <path>::<search>::<replace>...", "Batch replace local file with review"),
      new SlashCommand("/cmd <command>", "Run local command without model call"),
      new SlashCommand("/memory", "List, add, or delete persistent memories"),
      new SlashCommand("/todo", "List, add, update, or delete todo tasks"),
      new SlashCommand("/new", "Start a new session"),
      new SlashCommand("/resume", "Open saved session picker"),
      new SlashCommand("/fork", "Save current transcript into a new session"),
      new SlashCommand("/rename <name>", "Rename current session metadata"),
      new SlashCommand("/compact", "Compact middle conversation messages"),
      new SlashCommand("/config-paths", "Show config home directory"),
      new SlashCommand("/permissions", "Show permission storage and rule counts"),
      new SlashCommand("/exit", "Exit")
  );
  private record SessionPickerState(
      List<SessionStore.SessionSummary> sessions,
      int selectedIndex,
      int deleteConfirmIndex,
      boolean allProjects,
      List<SessionStore.ProjectMeta> projects,
      int projectIndex,
      String browseStorageName
  ) {}

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

      // Check for existing sessions
      try {
        var summaries = sessions.list();
        if (!summaries.isEmpty()) {
          addEntry(new TranscriptEntry.Assistant(nextEntryId++,
              "Found " + summaries.size() + " saved session(s). Type /resume to continue one."));
        }
      } catch (Exception ignored) {}

      // Discover external service status
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

  // --- Thread-safe transcript helpers ---

  private void addEntry(TranscriptEntry entry) {
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

  private void clearEntries() {
    synchronized (transcript) {
      transcript.clear();
    }
    transcriptDirty = true;
  }

  private int transcriptSize() {
    synchronized (transcript) {
      return transcript.size();
    }
  }

  private List<TranscriptEntry> transcriptSnapshot() {
    synchronized (transcript) {
      return new ArrayList<>(transcript);
    }
  }

  // --- Event loop ---

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
          handleEscapeSequence(readEscapeSequence(c));
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
          handleEscapeSequence(readEscapeSequence(c));
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
          // terminal.reader() is a java.io.Reader — it already decodes UTF-8 into
          // Unicode code points. Accept any printable character (>= 0x20).
          if (c >= 0x20) {
            compactNotification = null;
            slashMenuSelectedIndex = 0;
            insertText(String.valueOf(Character.toChars(c)));
          }
        }
      }
    }
  }

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

  private void handleResize() {
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
    if (seq.equals("[A") || seq.equals("OA")) {
      var cmds = getVisibleCommands();
      if (!cmds.isEmpty()) {
        slashMenuSelectedIndex = Math.max(0, slashMenuSelectedIndex - 1);
        render();
      } else {
        historyUp();
        render();
      }
    } else if (seq.equals("[B") || seq.equals("OB")) {
      var cmds = getVisibleCommands();
      if (!cmds.isEmpty()) {
        slashMenuSelectedIndex = Math.min(cmds.size() - 1, slashMenuSelectedIndex + 1);
        render();
      } else {
        historyDown();
        render();
      }
    } else if (seq.equals("[C") || seq.equals("OC")) {
      if (cursorPos < input.length()) { cursorPos++; render(); }
    } else if (seq.equals("[D") || seq.equals("OD")) {
      if (cursorPos > 0) { cursorPos--; render(); }
    } else if (seq.equals("[H") || seq.equals("[1~")) {
      cursorPos = 0; render();
    } else if (seq.equals("[F") || seq.equals("[4~")) {
      cursorPos = input.length(); render();
    } else if (seq.equals("[3~")) {
      if (cursorPos < input.length()) {
        input = input.substring(0, cursorPos) + input.substring(cursorPos + 1);
        render();
      }
    } else if (seq.equals("[5~")) {
      scrollTranscript(-SCROLL_STEP);
    } else if (seq.equals("[6~")) {
      scrollTranscript(SCROLL_STEP);
    } else if (seq.equals("[1;3A") || seq.equals("[1;5A")) { // Alt+Up / Ctrl+Up
      scrollTranscript(-1);
    } else if (seq.equals("[1;3B") || seq.equals("[1;5B")) { // Alt+Down / Ctrl+Down
      scrollTranscript(1);
    } else if (seq.length() > 3 && seq.charAt(2) == '<') {
      parseSgrMouse(seq);
    } else if (seq.length() == 6 && seq.charAt(1) == '[' && seq.charAt(2) == 'M') {
      // Legacy X10 mouse format: ESC[Mbtnxy (JLine Windows native terminal)
      parseX10Mouse(seq);
    } else if (seq.equals("")) {
      if (!input.isEmpty()) { input = ""; cursorPos = 0; render(); }
    }
  }

  private String readEscapeSequence(int first) throws IOException {
    var seq = new StringBuilder();
    seq.append((char) first);

    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40);
    while (System.nanoTime() < deadline) {
      if (!terminal.reader().ready()) {
        try {
          Thread.sleep(2);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        continue;
      }

      int next = terminal.reader().read();
      if (next < 0) break;
      seq.append((char) next);
      if (isCompleteEscapeSequence(seq)) break;
      // X10 mouse: ESC[M + 3 data bytes = 6 chars total
      if (seq.length() >= 6 && seq.charAt(1) == '[' && seq.charAt(2) == 'M') break;
    }
    return seq.toString();
  }

  static boolean isCompleteEscapeSequence(CharSequence seq) {
    int len = seq.length();
    if (len <= 1) return false;
    if (seq.charAt(1) == 'O') return len >= 3;
    if (seq.charAt(1) != '[') return true;
    if (len < 3) return false;
    char last = seq.charAt(len - 1);
    if (seq.charAt(2) == '<') {
      return last == 'M' || last == 'm';
    }
    // X10 mouse prefix ESC[M — wait for 3 data bytes (btn, x, y) in readEscapeSequence
    if (len == 3 && seq.charAt(2) == 'M') return false;
    return (last >= '@' && last <= '~');
  }

  /** Parse SGR mouse event: ESC[<button;col;rowM or ESC[<button;col;rowm */
  private void parseSgrMouse(String seq) {
    try {
      // Format: ESC[<button;col;rowM or m
      String inner = seq.substring(3, seq.length() - 1);
      String[] parts = inner.split(";");
      if (parts.length != 3) return;
      int btn = Integer.parseInt(parts[0]);
      if ((btn & 0x40) != 0) {
        // Scroll event
        int delta = (btn & 0x01) == 0 ? -3 : 3; // 64 = up, 65 = down
        scrollTranscript(delta);
      }
    } catch (Exception ignored) {
      // Malformed mouse event — skip silently
    }
  }

  /** Parse legacy X10 mouse event: ESC[Mbtnxy (JLine Windows native terminal). */
  private void parseX10Mouse(String seq) {
    try {
      // btn = charAt(3) - 32,  x = charAt(4) - 32,  y = charAt(5) - 32
      int btn = seq.charAt(3) - 0x20;
      if ((btn & 0x40) != 0) {
        // Scroll event: 64 (0x40) = up, 65 (0x41) = down
        int delta = (btn & 0x01) == 0 ? -3 : 3;
        scrollTranscript(delta);
      }
    } catch (Exception ignored) {
      // Malformed X10 mouse event — skip silently
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
          if (readEscapeSequence(c).equals("")) {
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
        String es = readEscapeSequence(c);
        if (es.equals("[A") || es.equals("OA")) {
          pendingApproval = new PendingApproval(pa.request(), pa.future(),
              Math.max(0, pa.selectedIndex() - 1));
          render();
        } else if (es.equals("[B") || es.equals("OB")) {
          pendingApproval = new PendingApproval(pa.request(), pa.future(),
              Math.min(choices.size() - 1, pa.selectedIndex() + 1));
          render();
        } else if (es.equals("")) {
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

  /** Update or create the transient status line entry with current spinner + text. */
  private void updateStatusLine() {
    String text = statusLineText;
    if (text == null || text.isBlank()) return;
    materializeProgressTrace();
  }

  /** Remove the transient status line entry if present. */
  private void clearStatusLine() {
    synchronized (transcript) {
      if (transcript.removeIf(e -> e instanceof TranscriptEntry.Status)) {
        transcriptDirty = true;
      }
    }
  }

  private String statusWithElapsed(String text) {
    if (!isBusy || busyStartedAtMillis <= 0) return text;
    long elapsed = Math.max(0, System.currentTimeMillis() - busyStartedAtMillis);
    long seconds = elapsed / 1000;
    if (seconds < 3) return text;
    return text + " (" + formatElapsed(seconds) + ")";
  }

  private static String formatElapsed(long seconds) {
    long minutes = seconds / 60;
    long rest = seconds % 60;
    return minutes > 0 ? minutes + "m " + rest + "s" : rest + "s";
  }

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

    if (text.equals("/exit")) {
      running = false;
      return;
    }

    addEntry(new TranscriptEntry.User(nextEntryId++, text));

    if (text.equals("/help")) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, """
          /help       Show commands
          /tools      List available tools
          /skills     List discovered skills
          /sessions   List saved sessions
          /status     Show workspace, session, and context stats
          /model      Show active model name
          /model <n>  Switch model and persist to user settings
          /mcp        Show MCP server and tool status
          /ls [path]  List local files without a model call
          /grep <pattern>::[path] Search local files
          /read <path> Read a local file
          /write <path>::<content> Write a local file with review
          /modify <path>::<content> Replace a local file with review
          /edit <path>::<search>::<replace> Edit a local file with review
          /patch <path>::<search>::<replace>... Batch replace a local file
          /cmd <command> Run a local command
          /memory list [query] List persistent memories
          /memory add <type>::<title>::<content> Save a memory
          /memory delete <id> Delete a memory
          /todo       List tasks, or add/done/undo/delete/clear
          /new        Start a new session
          /resume    Open saved session picker
          /resume <id> Load a saved session by id
          /fork       Save current transcript into a new session
          /rename <n> Rename current session metadata
          /compact    Compact middle conversation messages
          /config-paths Show config home directory
          /permissions Show permission storage and rule counts
          /exit       Exit"""));
      render();
      return;
    }

    if (text.equals("/tools")) {
      var sb = new StringBuilder();
      for (var t : tools.list()) {
        sb.append(t.name()).append(": ").append(t.description()).append("\n");
      }
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, sb.toString().trim()));
      render();
      return;
    }

    if (text.equals("/skills")) {
      var skills = new SkillService(cwd).discover();
      if (skills.isEmpty()) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "(none)"));
      } else {
        var sb = new StringBuilder();
        skills.forEach(s -> sb.append(s.name()).append(": ").append(s.skillFile()).append("\n"));
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, sb.toString().trim()));
      }
      render();
      return;
    }

    if (text.equals("/sessions")) {
      try {
        var summaries = sessions.list();
        if (summaries.isEmpty()) {
          addEntry(new TranscriptEntry.Assistant(nextEntryId++, "(none)"));
        } else {
          var sb = new StringBuilder();
          summaries.forEach(s ->
              sb.append(s.id()).append("  ").append(s.title()).append("  ").append(s.updatedAt()).append("\n"));
          addEntry(new TranscriptEntry.Assistant(nextEntryId++, sb.toString().trim()));
        }
      } catch (Throwable e) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Error: " + e.getMessage()));
      }
      render();
      return;
    }

    if (text.equals("/status")) {
      var stats = TokenEstimator.compute(messages, CONTEXT_WINDOW);
      var s = "workspace=" + cwd
          + "\nsession=" + sessionId
          + "\ntools=" + tools.list().size()
          + "\nskills=" + (skillCount >= 0 ? skillCount : "?")
          + "\nmcp=" + (mcpToolCount >= 0 ? mcpToolCount : "?")
          + "\nctx=" + stats.estimatedTokens() + " est tokens, level=" + stats.warningLevel();
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, s));
      render();
      return;
    }

    if (text.startsWith("/mcp")) {
      if (!text.equals("/mcp")) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Usage: /mcp"));
      } else {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, mcpStatus()));
      }
      render();
      return;
    }

    if (text.equals("/model")) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, config.model()));
      render();
      return;
    }

    if (text.startsWith("/model ")) {
      switchModel(text.substring("/model ".length()).trim());
      render();
      return;
    }

    if (tryLocalToolShortcut(text)) {
      render();
      return;
    }

    if (text.equals("/memory") || text.startsWith("/memory ")) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, runMemoryCommand(text)));
      render();
      return;
    }

    if (text.equals("/todo") || text.startsWith("/todo ")) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, runTodoCommand(text)));
      render();
      return;
    }

    if (text.equals("/new")) {
      sessionId = UUID.randomUUID().toString().substring(0, 8);
      messages.clear();
      messages.add(new ChatMessage.SystemMessage(systemPrompt()));
      savedCount = 1;
      clearEntries();
      transcriptScrollOffset = 0;
      transcriptAutoScroll = true;
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Session cleared. Starting fresh."));
      render();
      return;
    }

    if (text.equals("/fork")) {
      var newId = UUID.randomUUID().toString().substring(0, 8);
      try {
        // Auto-name fork: <current_title>_fork[<N>]
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
      render();
      return;
    }

    // /resume with or without argument
    if (text.equals("/resume")) {
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
      return;
    }

    if (text.startsWith("/resume ")) {
      String target = text.substring("/resume ".length()).trim();
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
      render();
      return;
    }

    if (text.startsWith("/rename ")) {
      String title = text.substring("/rename ".length()).trim();
      try {
        sessions.rename(sessionId, title);
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Renamed session to " + title));
      } catch (Exception e) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Rename failed: " + e.getMessage()));
      }
      render();
      return;
    }

    if (text.equals("/compact")) {
      runCompact();
      return;
    }

    if (text.equals("/config-paths")) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "home=" + RuntimeConfig.homeDir()));
      render();
      return;
    }

    if (text.equals("/permissions")) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, permissions.describePermissions()));
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

  private void switchModel(String modelName) {
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

  private String systemPrompt() {
    return InstructionLoader.systemPrompt(cwd, permissions.summary());
  }

  private String mcpStatus() {
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

  private boolean tryLocalToolShortcut(String text) {
    ObjectNode input = MAPPER.createObjectNode();
    String toolName = null;
    if (text.equals("/ls") || text.startsWith("/ls ")) {
      toolName = "list_files";
      String path = text.length() > 3 ? text.substring(3).trim() : ".";
      input.put("path", path.isBlank() ? "." : path);
    } else if (text.startsWith("/read ")) {
      toolName = "read_file";
      input.put("path", text.substring("/read ".length()).trim());
    } else if (text.startsWith("/grep ")) {
      toolName = "grep_files";
      String[] parts = splitShortcutPayload(text.substring("/grep ".length()).trim(), 2);
      input.put("pattern", parts[0]);
      input.put("path", parts.length > 1 && !parts[1].isBlank() ? parts[1] : ".");
    } else if (text.startsWith("/write ")) {
      toolName = "write_file";
      String[] parts = splitShortcutPayload(text.substring("/write ".length()).trim(), 2);
      if (parts.length < 2) return shortcutUsage("/write <path>::<content>");
      input.put("path", parts[0]);
      input.put("content", parts[1]);
    } else if (text.startsWith("/modify ")) {
      toolName = "modify_file";
      String[] parts = splitShortcutPayload(text.substring("/modify ".length()).trim(), 2);
      if (parts.length < 2) return shortcutUsage("/modify <path>::<content>");
      input.put("path", parts[0]);
      input.put("content", parts[1]);
    } else if (text.startsWith("/edit ")) {
      toolName = "edit_file";
      String[] parts = splitShortcutPayload(text.substring("/edit ".length()).trim(), 3);
      if (parts.length < 3) return shortcutUsage("/edit <path>::<search>::<replace>");
      input.put("path", parts[0]);
      input.put("oldText", parts[1]);
      input.put("newText", parts[2]);
    } else if (text.startsWith("/patch ")) {
      return runPatchShortcut(text.substring("/patch ".length()).trim());
    } else if (text.startsWith("/cmd ")) {
      toolName = "run_command";
      input.put("command", parseCmdShortcut(text.substring("/cmd ".length()).trim()));
    }

    if (toolName == null) return false;
    runShortcutTool(toolName, input);
    return true;
  }

  private boolean runPatchShortcut(String payload) {
    String[] parts = splitShortcutPayload(payload, 0);
    if (parts.length < 3 || parts.length % 2 == 0) {
      return shortcutUsage("/patch <path>::<search>::<replace>[::<search>::<replace>...]");
    }
    try {
      Path file = cwd.resolve(parts[0]).normalize();
      String before = java.nio.file.Files.readString(file);
      String after = before;
      for (int i = 1; i < parts.length; i += 2) {
        after = after.replace(parts[i], parts[i + 1]);
      }
      ObjectNode input = MAPPER.createObjectNode()
          .put("path", parts[0])
          .put("content", after);
      runShortcutTool("modify_file", input);
    } catch (Exception error) {
      addEntry(new TranscriptEntry.Tool(nextEntryId++, "patch",
          TranscriptEntry.ToolStatus.ERROR, error.getMessage()));
    }
    return true;
  }

  private String runTodoCommand(String text) {
    TodoStore store = new TodoStore(cwd);
    String rest = text.equals("/todo") ? "list" : text.substring("/todo ".length()).trim();

    if (rest.equals("list")) {
      var todos = store.list(null);
      if (todos.isEmpty()) return "(no todos)";
      StringBuilder out = new StringBuilder();
      int pending = 0, inProgress = 0, completed = 0;
      for (var t : todos) {
        String icon = switch (t.status()) {
          case "completed" -> { completed++; yield "[x]"; }
          case "in_progress" -> { inProgress++; yield "[>]"; }
          default -> { pending++; yield "[ ]"; }
        };
        out.append(icon).append(" ").append(t.id()).append(": ").append(t.content()).append("\n");
      }
      out.append("--- ").append(todos.size()).append(" total (")
          .append(pending).append(" pending, ")
          .append(inProgress).append(" in progress, ")
          .append(completed).append(" completed)");
      return out.toString();
    }
    if (rest.startsWith("add ")) {
      String content = rest.substring("add ".length()).trim();
      if (content.isBlank()) return "Usage: /todo add <content>";
      var entry = store.add(content, content);
      return "Added todo " + entry.id() + ": " + entry.content();
    }
    if (rest.startsWith("done ")) {
      String id = rest.substring("done ".length()).trim();
      var updated = store.update(id, "completed", null);
      if (updated == null) return "Todo not found: " + id;
      return "Completed todo " + id + ": " + updated.content();
    }
    if (rest.startsWith("undo ")) {
      String id = rest.substring("undo ".length()).trim();
      var updated = store.update(id, "pending", null);
      if (updated == null) return "Todo not found: " + id;
      return "Reset todo " + id + " to pending: " + updated.content();
    }
    if (rest.startsWith("delete ")) {
      String id = rest.substring("delete ".length()).trim();
      return store.delete(id) ? "Deleted todo " + id : "Todo not found: " + id;
    }
    if (rest.equals("clear")) {
      int removed = store.clearCompleted();
      return "Cleared " + removed + " completed todo(s)";
    }
    return "Usage: /todo [list] | /todo add <content> | /todo done <id> | /todo undo <id> | /todo delete <id> | /todo clear";
  }

  private String runMemoryCommand(String text) {
    String rest = text.equals("/memory") ? "list" : text.substring("/memory ".length()).trim();
    String toolName;
    ObjectNode input = MAPPER.createObjectNode();
    if (rest.equals("list") || rest.startsWith("list ")) {
      toolName = "list_memory";
      String query = rest.length() > 4 ? rest.substring(4).trim() : "";
      if (!query.isBlank()) input.put("query", query);
    } else if (rest.startsWith("add ")) {
      toolName = "save_memory";
      String[] parts = splitShortcutPayload(rest.substring("add ".length()).trim(), 3);
      if (parts.length < 3) return "Usage: /memory add <type>::<title>::<content>";
      input.put("type", parts[0]);
      input.put("title", parts[1]);
      input.put("content", parts[2]);
    } else if (rest.startsWith("delete ")) {
      toolName = "delete_memory";
      input.put("id", rest.substring("delete ".length()).trim());
    } else {
      return "Usage: /memory list [query] | /memory add <type>::<title>::<content> | /memory delete <id>";
    }
    var result = tools.execute(toolName, input, new ToolContext(cwd, permissions));
    return result.output();
  }

  private boolean shortcutUsage(String usage) {
    addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Usage: " + usage));
    return true;
  }

  private void runShortcutTool(String toolName, JsonNode input) {
    addEntry(new TranscriptEntry.Tool(nextEntryId++, toolName, TranscriptEntry.ToolStatus.RUNNING, input.toString()));
    var result = tools.execute(toolName, input, new ToolContext(cwd, permissions));
    recentTools.addLast(new ToolStatus(toolName, !result.ok()));
    if (recentTools.size() > 10) recentTools.removeFirst();
    addEntry(new TranscriptEntry.Tool(nextEntryId++, toolName,
        result.ok() ? TranscriptEntry.ToolStatus.SUCCESS : TranscriptEntry.ToolStatus.ERROR,
        result.output() == null ? "" : result.output()));
  }

  private static String[] splitShortcutPayload(String payload, int limit) {
    String[] parts = limit > 0 ? payload.split("::", limit) : payload.split("::", -1);
    for (int i = 0; i < parts.length; i++) {
      parts[i] = parts[i].trim();
    }
    return parts;
  }

  private String parseCmdShortcut(String payload) {
    String[] parts = splitShortcutPayload(payload, 2);
    if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
      try {
        if (java.nio.file.Files.isDirectory(cwd.resolve(parts[0]).normalize())) {
          return parts[1].isBlank() ? parts[0] : parts[1];
        }
      } catch (Exception ignored) {
        // Fall through and treat the full payload as a command.
      }
    }
    return payload;
  }

  private void runCompact() {
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
        // Remove transient status line before showing assistant content
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
            // Remove transient status line before finalizing assistant content
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
      recordProgressTrace(PROGRESS_RUNNING, "Running " + toolName);
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
  };

  private void recordProgressTrace(String line) {
    recordProgressTrace(PROGRESS_INFO, line);
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
    String runningLine = PROGRESS_RUNNING + "Running " + normalizeProgressLine(toolName);
    String completedLine = (isError ? PROGRESS_ERROR : PROGRESS_SUCCESS)
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
    recordProgressTrace(isError ? PROGRESS_ERROR : PROGRESS_SUCCESS,
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

  // --- Input helpers ---

  /** Insert text at cursor position and advance cursor. */
  private void insertText(String text) {
    if (cursorPos >= input.length()) {
      input += text;
    } else {
      input = input.substring(0, cursorPos) + text + input.substring(cursorPos);
    }
    cursorPos += text.length();
    render();
  }

  // --- Slash menu helpers ---

  private List<SlashCommand> getVisibleCommands() {
    if (!input.startsWith("/")) return List.of();
    if (input.equals("/")) return SLASH_COMMANDS;
    var matches = new ArrayList<SlashCommand>();
    for (var cmd : SLASH_COMMANDS) {
      if (cmd.usage().startsWith(input)) {
        matches.add(cmd);
      }
    }
    return matches;
  }

  private void fillSlashCommand(SlashCommand cmd) {
    input = cmd.usage();
    cursorPos = input.length();
    slashMenuSelectedIndex = 0;
    render();
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

  /** Load a session from another project directory. */
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
        String es = readEscapeSequence(c);
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
          // Enter on a project: load its sessions
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
              // Load session from another project
              String storageName = sp.browseStorageName();
              loadSessionFromProject(storageName, session.id());
            } else {
              loadSessionFromPicker(session.id());
            }
            render();
          }
        }
      }
      case 0x09 -> {
        // Toggle session / all-projects view
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
          // Second press: actually delete
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
          // First press: set delete confirmation
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

  // --- Rendering ---

  private synchronized void render() {
    try {
      renderUnsafe();
    } catch (Throwable error) {
      try {
        if (writer != null) {
          writer.write(Ansi.CLEAR);
          writer.write(Ansi.HIDE_CURSOR);
          writer.write(PanelRenderer.renderPanel("CodeAuto render error",
              (error.getMessage() == null ? error.toString() : error.getMessage())
                  + "\n\nThe TUI is still running. Press Ctrl+C to exit, or continue typing.",
              termWidth()));
          writer.flush();
        }
      } catch (Throwable ignored) {
        // Never let rendering errors terminate the TUI.
      }
    }
  }

  private void renderUnsafe() {
    var sb = new StringBuilder();

    sb.append("[H[J");
    sb.append(Ansi.HIDE_CURSOR);

    int termWidth = terminal.getSize().getColumns();
    int termHeight = terminal.getSize().getRows();

    String headerPanel = buildHeaderBody(termWidth);

    String bottomPanel;
    if (sessionPicker != null) {
      bottomPanel = renderSessionPickerPanel(termWidth);
    } else if (pendingApproval != null) {
      bottomPanel = renderApprovalPanel(termWidth);
    } else {
      bottomPanel = renderPromptPanel(termWidth);
    }

    int fixedLines = lineCount(headerPanel) + 1
        + lineCount(bottomPanel)
        + 1
        + 1;
    int transcriptPanelOverhead = 5;
    int transcriptMaxLines = Math.max(3, termHeight - fixedLines - transcriptPanelOverhead);

    String transcriptBody = buildTranscriptBody(termWidth, transcriptMaxLines);
    String rightTitle = transcriptSize() + " EVENTS";
    if (contextStats != null) {
      rightTitle += " - CTX=" + contextStats.estimatedTokens() + " - " + colorStatus(contextStats.warningLevel());
    }
    String transcriptPanel = PanelRenderer.renderFeedPanel("session feed", transcriptBody, termWidth, rightTitle);

    sb.append(headerPanel).append("\n");
    sb.append(transcriptPanel).append("\n\n");

    sb.append(bottomPanel);

    sb.append("\n").append(renderFooterBar(termWidth));

    // Clear any leftover content after the footer
    sb.append("[J");

    if (sessionPicker == null && pendingApproval == null) {
      int promptPanelStartRow = lineCount(headerPanel) + 1
          + lineCount(transcriptPanel) + 2;
      int safeCursor = Math.max(0, Math.min(cursorPos, input == null ? 0 : input.length()));
      int inputOffset = Ansi.stringDisplayWidth("CodeAuto> ")
          + Ansi.stringDisplayWidth((input == null ? "" : input).substring(0, safeCursor));
      int inputWidth = Math.max(1, termWidth);
      int cursorRow = promptPanelStartRow + 1 + (inputOffset / inputWidth);
      int cursorCol = 1 + (inputOffset % inputWidth);
      sb.append("\033[").append(Math.max(1, cursorRow)).append(";")
          .append(Math.max(1, Math.min(termWidth, cursorCol))).append("H");
    }

    try {
      synchronized (writer) {
        writer.write(sb.toString());
        writer.flush();
      }
    } catch (IOException e) {
      // ignore
    }
  }

  private String buildHeaderBody(int termWidth) {
    var sb = new StringBuilder();
    String cwdName = cwd.getFileName().toString();
    String modelName = config != null ? config.model() : "unknown";
    sb.append(Ansi.LIGHT_BLUE).append(Ansi.BOLD).append("CodeAuto").append(Ansi.RESET).append("  ");
    sb.append(Ansi.BLUE).append(Ansi.BOLD).append(Ansi.truncatePlain(cwdName, 24)).append(Ansi.RESET);
    sb.append(" ").append(Ansi.DIM).append(Ansi.truncatePathMiddle(cwd.toString(), 40)).append(Ansi.RESET);
    sb.append("\n");

    var badges = new ArrayList<String>();
    badges.add(metric("session", sessionId, Ansi.BRIGHT_YELLOW));
    badges.add(metric("model", modelName, Ansi.GREEN));
    badges.add(metric("messages", String.valueOf(messages.size()), Ansi.BRIGHT_CYAN));
    badges.add(metric("tools", String.valueOf(tools.list().size()), Ansi.MAGENTA));
    if (contextStats != null) {
      badges.add(renderContextBadge(contextStats));
    }
    badges.add(metric("skills", skillCount >= 0 ? String.valueOf(skillCount) : "?", Ansi.BRIGHT_CYAN));
    badges.add(renderTodoBadge());

    var badgeLine = joinBadges(badges, termWidth);
    sb.append(badgeLine);
    sb.append("\n");
    sb.append(Ansi.DARK_GRAY).append("─".repeat(Math.max(0, termWidth))).append(Ansi.RESET);

    return sb.toString();
  }

  private String renderContextBadge(ContextStats stats) {
    if (stats == null) return "";
    int pct = Math.min(100, Math.max(0,
        (int) ((double) stats.estimatedTokens() / CONTEXT_WINDOW * 100)));
    String color = switch (stats.warningLevel()) {
      case "warning" -> Ansi.YELLOW;
      case "critical" -> Ansi.RED;
      case "blocked" -> Ansi.BRIGHT_RED;
      default -> Ansi.GREEN;
    };
    return color + "ctx" + Ansi.RESET + " " + Ansi.BOLD + pct + "%" + Ansi.RESET;
  }

  private String renderTodoBadge() {
    try {
      var todos = new TodoStore(cwd).list(null);
      if (todos.isEmpty()) return "";
      long inProgress = todos.stream().filter(t -> "in_progress".equals(t.status())).count();
      long pending = todos.stream().filter(t -> "pending".equals(t.status())).count();
      long completed = todos.stream().filter(t -> "completed".equals(t.status())).count();
      long active = pending + inProgress;
      if (active == 0 && completed > 0) {
        return Ansi.GREEN + "todos" + Ansi.RESET + " " + Ansi.BOLD + "all done" + Ansi.RESET;
      }
      return Ansi.BRIGHT_YELLOW + "todos" + Ansi.RESET + " " + Ansi.BOLD + active + "/" + todos.size() + Ansi.RESET;
    } catch (Exception ignored) {
      return "";
    }
  }

  private String colorStatus(String level) {
    String normalized = level == null || level.isBlank() ? "ok" : level;
    String color = switch (normalized) {
      case "warning" -> Ansi.YELLOW;
      case "critical" -> Ansi.RED;
      case "blocked" -> Ansi.BRIGHT_RED;
      default -> Ansi.GREEN;
    };
    return color + normalized.toUpperCase() + Ansi.RESET;
  }

  private String metric(String label, String value, String color) {
    return color + label + Ansi.RESET + " " + Ansi.BOLD + value + Ansi.RESET;
  }

  private String joinBadges(List<String> badges, int maxWidth) {
    if (badges.isEmpty()) return "";
    String sep = Ansi.DARK_GRAY + " │ " + Ansi.RESET;
    String plain = String.join(sep, badges);
    if (Ansi.stringDisplayWidth(Ansi.stripAnsi(plain)) <= maxWidth) return plain;

    var result = new StringBuilder();
    for (String badge : badges) {
      String candidate = result.isEmpty() ? badge : result + Ansi.DARK_GRAY + " │ " + Ansi.RESET + badge;
      if (Ansi.stringDisplayWidth(Ansi.stripAnsi(candidate)) > maxWidth) break;
      if (result.isEmpty()) result.append(badge);
      else result.append(Ansi.DARK_GRAY).append(" │ ").append(Ansi.RESET).append(badge);
    }
    if (!result.isEmpty() && result.length() < plain.length()) {
      result.append("  ").append(Ansi.DIM).append("...").append(Ansi.RESET);
    }
    return result.toString();
  }

  private int termWidth() {
    return Math.max(20, terminal.getSize().getColumns());
  }

  private int transcriptHeight(int termHeight) {
    return Math.max(6, termHeight - 20);
  }

  private int lineCount(String text) {
    if (text == null || text.isEmpty()) return 0;
    return text.split("\n", -1).length;
  }

  private String buildTranscriptBody(int termWidth, int maxLines) {
    var pinnedProgress = buildPinnedProgressLines(termWidth);
    int pinnedLines = pinnedProgress.size();
    int transcriptBudget = Math.max(1, maxLines - pinnedLines);
    var lines = wrapDisplayLines(renderTranscriptLines(), Math.max(1, termWidth - 4));
    if (lines.isEmpty()) {
      lines = List.of("Type /help for commands.");
    }

    transcriptBudget = Math.max(1, transcriptBudget);
    int totalLines = lines.size();

    int maxOffset = totalLines <= transcriptBudget
        ? 0
        : Math.max(0, totalLines - Math.max(1, transcriptBudget - 1));
    if (transcriptAutoScroll) {
      transcriptScrollOffset = maxOffset;
    }

    transcriptScrollOffset = Math.max(0, Math.min(transcriptScrollOffset, maxOffset));

    // Re-enable auto-scroll if the user scrolled to the bottom
    if (!transcriptAutoScroll && transcriptScrollOffset >= maxOffset) {
      transcriptAutoScroll = true;
    }

    int start = transcriptScrollOffset;
    var sb = new StringBuilder();
    if (!pinnedProgress.isEmpty()) {
      sb.append(String.join("\n", pinnedProgress));
      if (transcriptBudget > 0) sb.append("\n");
    }

    // Scroll-up indicator
    if (start > 0) {
      sb.append(Ansi.DIM).append("↑ ").append(start).append(" more line").append(start != 1 ? "s" : "").append(Ansi.RESET).append("\n");
    }

    int end = Math.min(totalLines, start + transcriptBudget);
    int scrollIndicatorLines = (start > 0 ? 1 : 0);
    int available = transcriptBudget - scrollIndicatorLines;

    if (available > 0) {
      int endIndicatorLines = (end < totalLines ? 1 : 0);
      int actualEnd = Math.min(totalLines, start + available - endIndicatorLines);
      if (endIndicatorLines > 0 && actualEnd <= start) {
        actualEnd = start + 1;
      }

      for (int i = start; i < actualEnd; i++) {
        if (i > start) sb.append("\n");
        sb.append(lines.get(i));
      }

      // Scroll-down indicator
      if (actualEnd < totalLines) {
        int hidden = totalLines - actualEnd;
        if (sb.length() > 0) sb.append("\n");
        sb.append(Ansi.DIM).append("↓ ").append(hidden).append(" more line").append(hidden != 1 ? "s" : "").append(Ansi.RESET);
      }
    }

    return sb.toString();
  }

  private List<String> buildPinnedProgressLines(int termWidth) {
    if (!isBusy || progressTraceEntryId == null) return List.of();
    String body;
    synchronized (turnProgressTrace) {
      if (turnProgressTrace.isEmpty()) return List.of();
      body = String.join("\n", new ArrayList<>(turnProgressTrace));
    }

    var progress = new TranscriptEntry.Progress(
        progressTraceEntryId == null ? -1 : progressTraceEntryId,
        body);
    var lines = new ArrayList<String>();
    lines.add(Ansi.GRAY + "┄".repeat(Math.max(12, Math.min(72, termWidth - 8))) + Ansi.RESET);
    lines.add(Ansi.YELLOW + SPINNER_FRAMES[spinnerFrame] + " " + Ansi.BOLD + "progress" + Ansi.RESET);
    for (String line : renderProgressBody(progress).split("\n", -1)) {
      if (!line.isBlank()) {
        lines.add("  " + line);
      }
      if (lines.size() >= LIVE_PROGRESS_MAX_LINES) break;
    }
    while (lines.size() < LIVE_PROGRESS_MAX_LINES) {
      lines.add("");
    }
    return lines;
  }

  private List<String> renderTranscriptLines() {
    // Use cached lines if transcript hasn't changed
    if (!hasPinnedProgress() && !transcriptDirty && cachedRenderLines != null) {
      return cachedRenderLines;
    }

    var lines = new ArrayList<String>();
    String separator = Ansi.BLUE + Ansi.DIM + "·" + Ansi.RESET;

    List<TranscriptEntry> snapshot = transcriptSnapshot();

    for (int idx = 0; idx < snapshot.size(); idx++) {
      var entry = snapshot.get(idx);
      if (hasPinnedProgress()
          && progressTraceEntryId != null
          && entry instanceof TranscriptEntry.Progress p
          && p.id() == progressTraceEntryId) {
        continue;
      }
      // Compact separator: just a single line between rendered entries.
      if (!lines.isEmpty()) {
        lines.add(separator);
      }
      lines.addAll(List.of(renderTranscriptEntry(entry).split("\n")));
    }

    if (!hasPinnedProgress()) {
      cachedRenderLines = lines;
      transcriptDirty = false;
    }
    return lines;
  }

  private boolean hasPinnedProgress() {
    synchronized (turnProgressTrace) {
      return progressTraceEntryId != null && !turnProgressTrace.isEmpty();
    }
  }

  private List<String> wrapDisplayLines(List<String> inputLines, int width) {
    var wrapped = new ArrayList<String>();
    for (String line : inputLines) {
      wrapped.addAll(wrapDisplayLine(line, width));
    }
    return wrapped;
  }

  private List<String> wrapDisplayLine(String line, int width) {
    var parts = new ArrayList<String>();
    if (line == null || line.isEmpty() || width <= 0) {
      parts.add("");
      return parts;
    }

    String plain = Ansi.stripAnsi(line);
    if (Ansi.stringDisplayWidth(plain) <= width) {
      parts.add(line);
      return parts;
    }

    var current = new StringBuilder();
    int currentWidth = 0;
    for (int cp : plain.codePoints().toArray()) {
      int cw = Ansi.charDisplayWidth(cp);
      if (currentWidth + cw > width && currentWidth > 0) {
        parts.add(current.toString());
        current = new StringBuilder();
        currentWidth = 0;
      }
      current.appendCodePoint(cp);
      currentWidth += cw;
    }
    if (!current.isEmpty()) {
      parts.add(current.toString());
    }
    return parts;
  }

  private String renderTranscriptEntry(TranscriptEntry entry) {
    return switch (entry) {
      case TranscriptEntry.User u ->
          Ansi.GRAY + "┄".repeat(Math.max(12, Math.min(72, termWidth() - 8))) + Ansi.RESET + "\n"
              + Ansi.CYAN + Ansi.BOLD + "you" + Ansi.RESET + "\n" + indentBlock(u.body());
      case TranscriptEntry.Assistant a -> {
        String body = a.body();
        // Highlight error messages in red
        boolean isError = body != null
            && (body.startsWith("Error:") || body.startsWith("error:") || body.startsWith("Error\n"));
        String labelColor = isError ? Ansi.RED : Ansi.GREEN;
        yield Ansi.GRAY + "┄".repeat(Math.max(12, Math.min(72, termWidth() - 8))) + Ansi.RESET + "\n"
            + labelColor + Ansi.BOLD + "assistant" + Ansi.RESET + "\n"
            + indentBlock(MarkdownRenderer.render(body));
      }
      case TranscriptEntry.Status s ->
          Ansi.YELLOW + s.body() + Ansi.RESET;
      case TranscriptEntry.Progress p ->
          Ansi.GRAY + "┄".repeat(Math.max(12, Math.min(72, termWidth() - 8))) + Ansi.RESET + "\n"
              + Ansi.YELLOW + Ansi.BOLD + "progress" + Ansi.RESET
              + progressToggleHint(p.id())
              + "\n" + indentBlock(renderProgressBody(p));
      case TranscriptEntry.Tool t -> {
        String statusColor = switch (t.status()) {
          case RUNNING -> Ansi.YELLOW;
          case SUCCESS -> Ansi.GREEN;
          case ERROR -> Ansi.RED;
        };
        String statusLabel = switch (t.status()) {
          case RUNNING -> "running";
          case SUCCESS -> "ok";
          case ERROR -> "err";
        };
        yield Ansi.MAGENTA + Ansi.BOLD + "tool" + Ansi.RESET + " " + t.toolName() + " "
            + statusColor + statusLabel + Ansi.RESET;
      }
    };
  }

  private String progressToggleHint(int id) {
    return Ansi.DIM + (expandedProgressEntries.contains(id) ? " [-]" : " [+]") + Ansi.RESET;
  }

  private String renderProgressBody(TranscriptEntry.Progress progress) {
    String body = progress.body() == null ? "" : progress.body();
    List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));
    lines.removeIf(String::isBlank);
    lines.replaceAll(this::renderProgressLine);
    if (expandedProgressEntries.contains(progress.id()) || lines.size() <= 4) {
      return String.join("\n", lines);
    }
    List<String> compact = new ArrayList<>();
    compact.add(lines.getFirst());
    int historyToShow = Math.min(2, lines.size() - 1);
    int start = Math.max(1, lines.size() - historyToShow);
    for (int i = start; i < lines.size(); i++) {
      compact.add(lines.get(i));
    }
    int hidden = lines.size() - compact.size();
    if (hidden > 0) {
      compact.add(Ansi.DIM + "[+" + hidden + " progress lines hidden; Ctrl+O to expand]" + Ansi.RESET);
    }
    return String.join("\n", compact);
  }

  private String renderProgressLine(String raw) {
    if (raw.startsWith(PROGRESS_RUNNING)) {
      return SPINNER_FRAMES[spinnerFrame] + " " + raw.substring(PROGRESS_RUNNING.length());
    }
    if (raw.startsWith(PROGRESS_SUCCESS)) {
      return "[OK] " + raw.substring(PROGRESS_SUCCESS.length());
    }
    if (raw.startsWith(PROGRESS_ERROR)) {
      return "[ERR] " + raw.substring(PROGRESS_ERROR.length());
    }
    if (raw.startsWith(PROGRESS_INFO)) {
      return "· " + raw.substring(PROGRESS_INFO.length());
    }
    return raw;
  }

  private String indentBlock(String input) {
    if (input == null || input.isEmpty()) return "";
    var sb = new StringBuilder();
    boolean first = true;
    for (String line : input.split("\n")) {
      if (!first) sb.append("\n");
      sb.append("  ").append(line);
      first = false;
    }
    return sb.toString();
  }

  private String renderPromptPanel(int termWidth) {
    String promptLine = Ansi.LIGHT_BLUE + Ansi.BOLD + "CodeAuto>" + Ansi.RESET;

    String currentInput = input == null ? "" : input;
    int safeCursor = Math.max(0, Math.min(cursorPos, currentInput.length()));
    String before = currentInput.substring(0, safeCursor);
    String at = safeCursor < currentInput.length() ? String.valueOf(currentInput.charAt(safeCursor)) : " ";
    String after = safeCursor < currentInput.length() ? currentInput.substring(safeCursor + 1) : "";

    String placeholder = currentInput.isEmpty()
        ? Ansi.DIM + " Ask for code, files, tasks, or MCP tools" + Ansi.RESET
        : "";

    var inputLine = new StringBuilder();
    inputLine.append(promptLine).append(" ").append(before);
    if (cursorBlinkVisible) {
      inputLine.append(Ansi.REVERSE).append(at).append(Ansi.RESET);
    } else {
      inputLine.append(at);
    }
    inputLine.append(after);
    inputLine.append(placeholder);

    // Slash menu: show matching commands when input starts with /
    var body = new StringBuilder();
    body.append(Ansi.DARK_GRAY).append("─".repeat(Math.max(0, termWidth))).append(Ansi.RESET)
        .append("\n")
        .append(inputLine)
        .append("\n")
        .append(Ansi.DIM)
        .append("Enter send | Esc clear | Ctrl+C interrupt/exit | Ctrl+O progress | Ctrl+↑/↓ scroll")
        .append(Ansi.RESET);

    var visCmds = getVisibleCommands();
    if (!visCmds.isEmpty()) {
      body.append("\n");
      int cmdWidth = Math.max(24, termWidth - 6);
      int selected = Math.max(0, Math.min(slashMenuSelectedIndex, visCmds.size() - 1));
      int start = Math.max(0, selected - SLASH_MENU_MAX_ROWS / 2);
      int end = Math.min(visCmds.size(), start + SLASH_MENU_MAX_ROWS);
      start = Math.max(0, end - SLASH_MENU_MAX_ROWS);
      for (int i = start; i < end; i++) {
        var cmd = visCmds.get(i);
        String prefix = (i == selected)
            ? Ansi.REVERSE + "> " + Ansi.RESET
            : "  ";
        String usage = Ansi.BOLD + cmd.usage() + Ansi.RESET;
        String desc = Ansi.DIM + cmd.description() + Ansi.RESET;
        int usageWidth = Ansi.stringDisplayWidth(Ansi.stripAnsi(cmd.usage()));
        int pad = Math.max(1, cmdWidth - usageWidth);
        body.append("\n").append(prefix).append(" ").append(usage);
        body.append(" ".repeat(pad)).append(desc);
      }
      if (visCmds.size() > SLASH_MENU_MAX_ROWS) {
        body.append("\n")
            .append(Ansi.DIM)
            .append("  ")
            .append(selected + 1)
            .append("/")
            .append(visCmds.size())
            .append(" matches, Up/Down select")
            .append(Ansi.RESET);
      }
    }

    return body.toString();
  }

  private String renderApprovalPanel(int termWidth) {
    var pa = pendingApproval;
    if (pa == null) return "";

    // Feedback input mode
    if (approvalFeedbackMode) {
      var fb = new StringBuilder();
      fb.append(Ansi.YELLOW).append(Ansi.BOLD).append("Reject With Guidance").append(Ansi.RESET).append("\n");
      fb.append(Ansi.DIM).append("Type feedback for model, Enter submit, Esc back").append(Ansi.RESET).append("\n\n");

      String fbText = approvalFeedbackInput.toString();
      String before = fbText;
      String at = " ";
      if (fbText.isEmpty()) {
        at = " ";
      } else {
        before = fbText;
        at = " ";
      }
      fb.append(Ansi.BOLD).append("feedback> ").append(Ansi.RESET);
      fb.append(before);
      fb.append(Ansi.REVERSE).append(at).append(Ansi.RESET);

      return PanelRenderer.renderLightPanel("approval", fb.toString(), termWidth);
    }

    var req = pa.request();

    var sb = new StringBuilder();
    sb.append(Ansi.YELLOW).append(Ansi.BOLD).append("Approval Required").append(Ansi.RESET).append("\n");
    sb.append(Ansi.BOLD).append(req.summary()).append(Ansi.RESET).append("\n");
    sb.append(Ansi.DIM).append(req.scope()).append(Ansi.RESET).append("\n\n");

    var choices = req.choices();
    for (int i = 0; i < choices.size(); i++) {
      String label = switch (choices.get(i)) {
        case ALLOW_ONCE -> "Allow Once";
        case ALLOW_ALWAYS -> "Allow Always";
        case ALLOW_TURN -> "Allow This Turn";
        case ALLOW_ALL_TURN -> "Allow All This Turn";
        case DENY_ONCE -> "Deny";
        case DENY_ALWAYS -> "Deny Always";
        case DENY_WITH_FEEDBACK -> "Deny with Feedback";
      };
      String prefix = i == pa.selectedIndex()
          ? Ansi.REVERSE + "> " + Ansi.RESET
          : "  ";
      sb.append(prefix).append(" ").append(label).append("\n");
    }

    sb.append("\n").append(Ansi.DIM).append("Up/Down select, Enter confirm, Esc deny · y/n 1-7 shortcuts").append(Ansi.RESET);

    return PanelRenderer.renderLightPanel("approval", sb.toString(), termWidth);
  }

  private String renderSessionPickerPanel(int termWidth) {
    var sp = sessionPicker;
    if (sp == null) return "";

    var sb = new StringBuilder();
    if (sp.allProjects()) {
      var projects = sp.projects();
      if (projects.isEmpty()) {
        sb.append(Ansi.DIM).append("(no other projects found)").append(Ansi.RESET);
      } else {
        int start = Math.max(0, Math.min(sp.projectIndex() - 3, Math.max(0, projects.size() - 7)));
        int end = Math.min(projects.size(), start + 7);
        if (start > 0) sb.append(Ansi.DIM).append("... ").append(start).append(" more projects").append(Ansi.RESET).append("\n");
        for (int i = start; i < end; i++) {
          var p = projects.get(i);
          String prefix = i == sp.projectIndex()
              ? Ansi.REVERSE + "> " + Ansi.RESET
              : "  ";
          sb.append(prefix).append(" ").append(Ansi.BOLD).append(p.cwd()).append(Ansi.RESET);
          sb.append("  ").append(Ansi.DIM).append(p.sessionCount()).append(" sessions").append(Ansi.RESET);
          sb.append("\n");
        }
        if (end < projects.size()) sb.append(Ansi.DIM).append("... ").append(projects.size() - end).append(" more projects").append(Ansi.RESET).append("\n");
      }
    } else {
      var sessions = sp.sessions();
      if (sessions.isEmpty()) {
        sb.append(Ansi.DIM).append("(no saved sessions)").append(Ansi.RESET);
      } else {
        int start = Math.max(0, Math.min(sp.selectedIndex() - 3, Math.max(0, sessions.size() - 7)));
        int end = Math.min(sessions.size(), start + 7);
        if (start > 0) sb.append(Ansi.DIM).append("... ").append(start).append(" more sessions").append(Ansi.RESET).append("\n");
        for (int i = start; i < end; i++) {
          var s = sessions.get(i);
          String prefix = i == sp.selectedIndex()
              ? Ansi.REVERSE + "> " + Ansi.RESET
              : "  ";
          sb.append(prefix).append(" ");
          sb.append(Ansi.BRIGHT_YELLOW).append(s.id()).append(Ansi.RESET).append("  ");
          sb.append(Ansi.BOLD).append(Ansi.truncatePlain(s.title(), 50)).append(Ansi.RESET);
          if (sp.deleteConfirmIndex() == i) {
            sb.append("  ").append(Ansi.YELLOW).append(Ansi.BOLD)
              .append("[DELETE? Press 'd' again to confirm]").append(Ansi.RESET);
          }
          sb.append("\n");
        }
        if (end < sessions.size()) sb.append(Ansi.DIM).append("... ").append(sessions.size() - end).append(" more sessions").append(Ansi.RESET).append("\n");
      }
    }

    sb.append("\n").append(Ansi.DIM);
    if (sp.allProjects()) {
      sb.append("Up/Down navigate, Tab sessions, Esc cancel");
    } else {
      sb.append("Up/Down navigate, Enter select, d delete, Tab projects, Esc cancel");
    }
    sb.append(Ansi.RESET);

    return PanelRenderer.renderLightPanel("session picker", sb.toString(), termWidth);
  }

  /** Render a compact tool panel showing running + recent tools. */
  private String renderToolPanel(int termWidth) {
    if (runningToolName == null && recentTools.isEmpty()) return "";

    var sb = new StringBuilder();
    if (runningToolName != null) {
      sb.append(Ansi.YELLOW).append("▶ ").append(Ansi.BOLD).append(runningToolName).append(Ansi.RESET);
      sb.append("  ").append(Ansi.DIM).append("running").append(Ansi.RESET);
    }
    if (!recentTools.isEmpty()) {
      if (runningToolName != null) sb.append("\n");
      sb.append(Ansi.DIM).append("recent:").append(Ansi.RESET).append(" ");
      int count = 0;
      for (var tool : recentTools.reversed()) {
        if (count > 0) sb.append("  ");
        String color = tool.isError() ? Ansi.RED : Ansi.GREEN;
        String icon = tool.isError() ? "✗" : "✓";
        sb.append(color).append(icon).append(Ansi.RESET).append(" ").append(tool.name());
        count++;
        if (count >= 5) break;
      }
    }

    return PanelRenderer.renderPanel("tools", sb.toString(), termWidth);
  }

  private String renderFooterBar(int termWidth) {
    var left = new StringBuilder();
    if (statusText != null) {
      left.append(Ansi.YELLOW)
          .append(Ansi.BOLD)
          .append(SPINNER_FRAMES[spinnerFrame])
          .append(" ")
          .append(statusWithElapsed(statusText))
          .append(Ansi.RESET);
    } else {
      left.append(Ansi.DIM).append("Ready").append(Ansi.RESET);
    }

    // Show running background tasks
    var right = new StringBuilder();
    var bgTasks = BackgroundTaskRegistry.get().list();
    long runningCount = bgTasks.stream().filter(t -> "running".equals(t.status())).count();
    if (runningCount > 0) {
      right.append("  ").append(Ansi.DIM).append("shells").append(Ansi.RESET)
          .append(" ").append(Ansi.BRIGHT_CYAN).append(runningCount).append(Ansi.RESET);
    }

    // Scroll indicator when not at bottom
    if (transcriptScrollOffset > 0) {
      left.append("  ").append(Ansi.DIM).append("↑ scroll").append(Ansi.RESET);
    }

    // Compact notification badge
    if (compactNotification != null) {
      left.append("  ").append(Ansi.YELLOW).append(Ansi.BOLD).append(compactNotification).append(Ansi.RESET);
    }

    int contentWidth = Math.max(1, termWidth - 2);
    String leftText = left.toString();
    String rightText = right.toString();
    int leftLen = Ansi.stringDisplayWidth(leftText);
    int rightLen = Ansi.stringDisplayWidth(rightText);
    if (leftLen + rightLen + 1 > contentWidth) {
      int rightBudget = Math.min(rightLen, Math.max(8, contentWidth / 2));
      rightText = Ansi.DIM + Ansi.truncatePlain(Ansi.stripAnsi(rightText), rightBudget) + Ansi.RESET;
      rightLen = Ansi.stringDisplayWidth(rightText);
      leftText = Ansi.truncatePlain(Ansi.stripAnsi(leftText), Math.max(0, contentWidth - rightLen - 1));
      leftLen = Ansi.stringDisplayWidth(leftText);
    }
    int gap = Math.max(1, contentWidth - leftLen - rightLen);

    return leftText + " ".repeat(gap) + rightText;
  }
}
