package com.codeauto.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.config.RuntimeConfig;
import com.codeauto.context.CompactService;
import com.codeauto.context.ContextStats;
import com.codeauto.context.TokenEstimator;
import com.codeauto.core.AgentLoop;
import com.codeauto.core.AgentLoopListener;
import com.codeauto.core.ChatMessage;
import com.codeauto.manage.ManagementStore;
import com.codeauto.mcp.McpService;
import com.codeauto.model.ModelAdapter;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.permissions.PermissionPrompt;
import com.codeauto.permissions.PermissionRequest;
import com.codeauto.permissions.PermissionResponse;
import com.codeauto.session.SessionStore;
import com.codeauto.skills.SkillService;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class TuiApp {
  private static final int CONTEXT_WINDOW = 200_000;
  private static final int PERMISSION_TIMEOUT_SECS = 120;
  private static final int SCROLL_STEP = 5;
  private static final int TOOL_PREVIEW_LINES = 3;
  private static final int TOOL_COLLAPSE_CHARS = 400;

  private final ToolRegistry tools;
  private final ModelAdapter model;
  private final Path cwd;
  private final int maxSteps;
  private final RuntimeConfig config;

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
  private String statusText;
  private final Deque<ToolStatus> recentTools = new ArrayDeque<>();
  private String runningToolName;
  private ContextStats contextStats;
  private final List<String> history = new ArrayList<>();
  private int historyIndex;
  private String historyDraft = "";
  private volatile PendingApproval pendingApproval;
  private volatile boolean running = true;

  // Scrolling
  private int transcriptScrollOffset;
  private boolean transcriptAutoScroll = true;

  // Tool output expand/collapse
  private final Set<Integer> expandedTools = ConcurrentHashMap.newKeySet();

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
      new SlashCommand("/model", "Show active model name"),
      new SlashCommand("/new", "Start a new session"),
      new SlashCommand("/resume <id>", "Load a saved session"),
      new SlashCommand("/fork", "Save current transcript into a new session"),
      new SlashCommand("/rename <name>", "Rename current session metadata"),
      new SlashCommand("/compact", "Compact middle conversation messages"),
      new SlashCommand("/config-paths", "Show config home directory"),
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
      messages.add(new ChatMessage.SystemMessage("You are CodeAuto. Permissions: " + permissions.summary()));

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

      terminal.handle(Terminal.Signal.WINCH, signal -> handleResize());
      handleResize();
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

  private void eventLoop() throws IOException {
    var buf = new StringBuilder();
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
          running = false;
          break;
        }
        if (c == 0x1B) {
          compactNotification = null;
          buf.setLength(0);
          buf.append((char) c);
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          while (terminal.reader().ready()) {
            int next = terminal.reader().read();
            if (next < 0) break;
            buf.append((char) next);
          }
          handleEscapeSequence(buf.toString());
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
          buf.setLength(0);
          buf.append((char) c);
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          while (terminal.reader().ready()) {
            int next = terminal.reader().read();
            if (next < 0) break;
            buf.append((char) next);
          }
          handleEscapeSequence(buf.toString());
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
        case 0x0F -> toggleToolExpand(); // Ctrl+O
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
    String lower = input.toLowerCase();
    if (input.isEmpty() || "/".equals(input)) {
      input = "/";
      cursorPos = 1;
    } else if (lower.equals("/")) {
      input = "/help";
      cursorPos = input.length();
    } else if (lower.equals("/h")) {
      input = "/help";
      cursorPos = input.length();
    } else if (lower.equals("/t")) {
      input = "/tools";
      cursorPos = input.length();
    } else if (lower.equals("/s")) {
      input = "/status";
      cursorPos = input.length();
    } else if (lower.equals("/sk")) {
      input = "/skills";
      cursorPos = input.length();
    } else if (lower.equals("/se")) {
      input = "/sessions";
      cursorPos = input.length();
    } else if (lower.equals("/m")) {
      input = "/model";
      cursorPos = input.length();
    } else if (lower.equals("/n")) {
      input = "/new";
      cursorPos = input.length();
    } else if (lower.equals("/f")) {
      input = "/fork";
      cursorPos = input.length();
    } else if (lower.equals("/re")) {
      input = "/resume ";
      cursorPos = input.length();
    } else if (lower.equals("/ren")) {
      input = "/rename ";
      cursorPos = input.length();
    } else if (lower.equals("/c")) {
      input = "/compact";
      cursorPos = input.length();
    } else if (lower.equals("/co")) {
      input = "/config-paths";
      cursorPos = input.length();
    } else if (lower.equals("/e")) {
      input = "/exit";
      cursorPos = input.length();
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
    } else if (seq.equals("")) {
      if (!input.isEmpty()) { input = ""; cursorPos = 0; render(); }
    }
  }

  /** Parse SGR mouse event: ESC[<row;col;btnM or ESC[<row;col;btnm */
  private void parseSgrMouse(String seq) {
    try {
      // Format: ESC[<row;col;btnM or m
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
          var seq = new StringBuilder();
          seq.append((char) c);
          try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          while (terminal.reader().ready()) {
            int next = terminal.reader().read();
            if (next < 0) break;
            seq.append((char) next);
          }
          if (seq.toString().equals("")) {
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
        var seq = new StringBuilder();
        seq.append((char) c);
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        while (terminal.reader().ready()) {
          int next = terminal.reader().read();
          if (next < 0) break;
          seq.append((char) next);
        }
        String es = seq.toString();
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

  /** Toggle expand/collapse on the most recent non-running tool entry. */
  private void toggleToolExpand() {
    int size = transcriptSize();
    for (int i = size - 1; i >= 0; i--) {
      TranscriptEntry entry;
      synchronized (transcript) {
        entry = transcript.get(i);
      }
      if (entry instanceof TranscriptEntry.Tool t
          && t.status() != TranscriptEntry.ToolStatus.RUNNING) {
        // Toggle expansion of this entry
        if (expandedTools.contains(t.id())) {
          expandedTools.remove(t.id());
        } else {
          expandedTools.add(t.id());
        }
        transcriptDirty = true;
        render();
        break;
      }
    }
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
          /new        Start a new session
          /resume <id> Load a saved session
          /fork       Save current transcript into a new session
          /rename <n> Rename current session metadata
          /compact    Compact middle conversation messages
          /config-paths Show config home directory
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
      } catch (Exception e) {
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

    if (text.equals("/model")) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, config.model()));
      render();
      return;
    }

    if (text.equals("/new")) {
      sessionId = UUID.randomUUID().toString().substring(0, 8);
      messages.clear();
      messages.add(new ChatMessage.SystemMessage("You are CodeAuto. Permissions: " + permissions.summary()));
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
          messages.add(new ChatMessage.SystemMessage("You are CodeAuto. Permissions: " + permissions.summary()));
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

    // Submit to AgentLoop
    isBusy = true;
    statusText = "Thinking...";
    render();

    messages.add(new ChatMessage.UserMessage(text));

    CompletableFuture.runAsync(() -> {
      try {
        permissions.beginTurn();
        var nextMessages = new ArrayList<>(loop.runTurn(messages));
        permissions.endTurn();
        messages.clear();
        messages.addAll(nextMessages);
        sessions.save(sessionId, messages, savedCount);
        savedCount = messages.size();
      } catch (Exception e) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Error: " + e.getMessage()));
      } finally {
        isBusy = false;
        transcriptAutoScroll = true;
        statusText = null;
        contextStats = TokenEstimator.compute(messages, CONTEXT_WINDOW);
        render();
      }
    });
  }

  private void runCompact() {
    if (messages.size() <= 2) {
      addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Not enough conversation to compress."));
      render();
      return;
    }
    isBusy = true;
    statusText = "Compressing...";
    render();

    CompletableFuture.runAsync(() -> {
      try {
        int before = messages.size();
        var result = CompactService.compactWithStats(messages, 8);
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
      } catch (Exception e) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, "Compression failed: " + e.getMessage()));
      } finally {
        isBusy = false;
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
        addEntry(new TranscriptEntry.Progress(nextEntryId++, content));
        render();
      }
    }

    @Override
    public void onAssistantMessage(String content) {
      if (content != null && !content.isBlank()) {
        addEntry(new TranscriptEntry.Assistant(nextEntryId++, content));
        render();
      }
    }

    @Override
    public void onToolStart(String toolName, JsonNode input) {
      runningToolName = toolName;
      statusText = "Running " + toolName + "...";
      String inputSummary = input != null ? input.toString() : "";
      if (inputSummary.length() > 200) inputSummary = inputSummary.substring(0, 200) + "...";
      addEntry(new TranscriptEntry.Tool(nextEntryId++, toolName,
          TranscriptEntry.ToolStatus.RUNNING, inputSummary));
      render();
    }

    @Override
    public void onToolResult(String toolName, String output, boolean isError) {
      runningToolName = null;
      recentTools.addLast(new ToolStatus(toolName, isError));
      if (recentTools.size() > 10) recentTools.removeFirst();
      statusText = "Thinking...";
      // Update the latest running tool entry for this tool
      synchronized (transcript) {
        for (int i = transcript.size() - 1; i >= 0; i--) {
          var entry = transcript.get(i);
          if (entry instanceof TranscriptEntry.Tool t && t.toolName().equals(toolName)
              && t.status() == TranscriptEntry.ToolStatus.RUNNING) {
            String body = output;
            if (body != null && body.length() > 2000) body = body.substring(0, 2000) + "...\n[truncated]";
            transcript.set(i, new TranscriptEntry.Tool(t.id(), toolName,
                isError ? TranscriptEntry.ToolStatus.ERROR : TranscriptEntry.ToolStatus.SUCCESS, body));
            break;
          }
        }
      }
      transcriptDirty = true;
      render();
    }
  };

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
        messages.add(new ChatMessage.SystemMessage("You are CodeAuto. Permissions: " + permissions.summary()));
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
        messages.add(new ChatMessage.SystemMessage("You are CodeAuto. Permissions: " + permissions.summary()));
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
        var seq = new StringBuilder();
        seq.append((char) c);
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        while (terminal.reader().ready()) {
          int next = terminal.reader().read();
          if (next < 0) break;
          seq.append((char) next);
        }
        String es = seq.toString();
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

  /** Apply ANSI color highlighting to unified diff output with word-level changes. */
  private static String highlightDiff(String text) {
    if (text == null || text.isEmpty()) return text;
    var sb = new StringBuilder();
    var lines = text.lines().toList();
    String pendingRemoved = null;

    for (var line : lines) {
      if (line.startsWith("-") && !line.startsWith("---")) {
        pendingRemoved = line;
        continue;
      }

      if (line.startsWith("+") && !line.startsWith("+++") && pendingRemoved != null) {
        sb.append(wordDiff(pendingRemoved, line)).append("\n");
        pendingRemoved = null;
        continue;
      }

      if (pendingRemoved != null) {
        sb.append(Ansi.RED).append(pendingRemoved).append(Ansi.RESET).append("\n");
        pendingRemoved = null;
      }

      if (line.startsWith("@@")) {
        sb.append(Ansi.CYAN).append(Ansi.BOLD).append(line).append(Ansi.RESET).append("\n");
      } else if (line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("diff --git")) {
        sb.append(Ansi.DIM).append(line).append(Ansi.RESET).append("\n");
      } else if (line.startsWith("+")) {
        sb.append(Ansi.GREEN).append(line).append(Ansi.RESET).append("\n");
      } else {
        sb.append(line).append("\n");
      }
    }

    if (pendingRemoved != null) {
      sb.append(Ansi.RED).append(pendingRemoved).append(Ansi.RESET).append("\n");
    }

    return sb.toString().stripTrailing();
  }

  /** Bold the differing text between a removed and added diff line. */
  private static String wordDiff(String removedLine, String addedLine) {
    String rem = removedLine.substring(1);
    String add = addedLine.substring(1);

    int prefixLen = 0;
    int minLen = Math.min(rem.length(), add.length());
    while (prefixLen < minLen && rem.charAt(prefixLen) == add.charAt(prefixLen)) {
      prefixLen++;
    }

    int suffixLen = 0;
    while (suffixLen < minLen - prefixLen
        && rem.charAt(rem.length() - 1 - suffixLen) == add.charAt(add.length() - 1 - suffixLen)) {
      suffixLen++;
    }

    String prefix = rem.substring(0, prefixLen);
    String remMiddle = rem.substring(prefixLen, rem.length() - suffixLen);
    String addMiddle = add.substring(prefixLen, add.length() - suffixLen);
    String suffix = rem.length() - suffixLen > 0 ? rem.substring(rem.length() - suffixLen) : "";

    var sb = new StringBuilder();
    sb.append(Ansi.RED).append("-");
    sb.append(prefix);
    if (!remMiddle.isEmpty()) sb.append(Ansi.BOLD).append(remMiddle).append(Ansi.RESET).append(Ansi.RED);
    sb.append(suffix);
    sb.append(Ansi.RESET);
    sb.append("\n");
    sb.append(Ansi.GREEN).append("+");
    sb.append(prefix);
    if (!addMiddle.isEmpty()) sb.append(Ansi.BOLD).append(addMiddle).append(Ansi.RESET).append(Ansi.GREEN);
    sb.append(suffix);
    sb.append(Ansi.RESET);

    return sb.toString();
  }

  // --- Rendering ---

  private void render() {
    var sb = new StringBuilder();

    // Flicker-free update: cursor home + overwrite + clear to end
    // instead of clearing the entire screen which causes a visible flash
    sb.append("[H");
    sb.append(Ansi.HIDE_CURSOR);

    int termWidth = terminal.getSize().getColumns();
    int termHeight = terminal.getSize().getRows();

    String headerBody = buildHeaderBody();
    String headerPanel = PanelRenderer.renderPanel("CodeAuto", headerBody, termWidth);

    String toolPanel = "";
    if (sessionPicker == null && pendingApproval == null) {
      toolPanel = renderToolPanel(termWidth);
    }

    String bottomPanel;
    if (sessionPicker != null) {
      bottomPanel = renderSessionPickerPanel(termWidth);
    } else if (pendingApproval != null) {
      bottomPanel = renderApprovalPanel(termWidth);
    } else {
      bottomPanel = renderPromptPanel(termWidth);
    }

    int fixedLines = lineCount(headerPanel) + 2
        + (toolPanel.isEmpty() ? 0 : lineCount(toolPanel) + 2)
        + lineCount(bottomPanel)
        + 1
        + 1;
    int transcriptPanelOverhead = 4;
    int transcriptMaxLines = Math.max(1, termHeight - fixedLines - transcriptPanelOverhead);

    String transcriptBody = buildTranscriptBody(termWidth, transcriptMaxLines);
    String rightTitle = transcriptSize() + " events";
    if (contextStats != null) {
      rightTitle += " | ctx=" + contextStats.estimatedTokens() + " [" + contextStats.warningLevel() + "]";
    }
    String transcriptPanel = PanelRenderer.renderPanel("session feed", transcriptBody, termWidth, rightTitle);

    sb.append(headerPanel).append("\n\n");
    sb.append(transcriptPanel).append("\n\n");

    if (!toolPanel.isEmpty()) {
      sb.append(toolPanel).append("\n\n");
    }

    sb.append(bottomPanel);

    sb.append("\n").append(renderFooterBar(termWidth));

    // Clear any leftover content after the footer
    sb.append("[J");

    if (sessionPicker == null && pendingApproval == null) {
      int promptStartRow = lineCount(headerPanel) + 2
          + lineCount(transcriptPanel) + 2
          + (toolPanel.isEmpty() ? 0 : lineCount(toolPanel) + 2)
          + 1;
      int inputOffset = Ansi.stringDisplayWidth("mini-code> ")
          + Ansi.stringDisplayWidth(input.substring(0, Math.min(cursorPos, input.length())));
      int innerWidth = Math.max(1, termWidth - 4);
      int cursorRow = promptStartRow + 5 + (inputOffset / innerWidth);
      int cursorCol = 3 + (inputOffset % innerWidth);
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

  private String buildHeaderBody() {
    var sb = new StringBuilder();
    sb.append(Ansi.DIM).append("Java terminal coding assistant.").append(Ansi.RESET).append("\n\n");

    String cwdName = cwd.getFileName().toString();
    String modelName = config != null ? config.model() : "unknown";
    sb.append(Ansi.BLUE).append(Ansi.BOLD).append(Ansi.truncatePlain(cwdName, 24)).append(Ansi.RESET);
    sb.append(" ").append(Ansi.DIM).append(Ansi.truncatePathMiddle(cwd.toString(), 40)).append(Ansi.RESET);
    sb.append("\n");

    var badges = new ArrayList<String>();
    badges.add(colorBadge("session", sessionId, Ansi.BRIGHT_YELLOW));
    badges.add(colorBadge("model", modelName, Ansi.GREEN));
    badges.add(colorBadge("messages", String.valueOf(messages.size()), Ansi.BRIGHT_CYAN));
    badges.add(colorBadge("tools", String.valueOf(tools.list().size()), Ansi.MAGENTA));
    if (contextStats != null) {
      badges.add(renderContextBadge(contextStats));
    }

    var badgeLine = joinBadges(badges, termWidth());
    sb.append(badgeLine).append("\n");
    sb.append(Ansi.DIM).append("permissions: ask on sensitive actions").append(Ansi.RESET);

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
    return color + "[ctx]" + Ansi.RESET + " " + Ansi.BOLD + pct + "%" + Ansi.RESET;
  }

  private String colorBadge(String label, String value, String color) {
    return color + "[" + label + "]" + Ansi.RESET + " " + Ansi.BOLD + value + Ansi.RESET;
  }

  private String joinBadges(List<String> badges, int maxWidth) {
    if (badges.isEmpty()) return "";
    String plain = String.join("  ", badges);
    if (Ansi.stringDisplayWidth(Ansi.stripAnsi(plain)) <= maxWidth) return plain;

    var result = new StringBuilder();
    for (String badge : badges) {
      String candidate = result.isEmpty() ? badge : result + "  " + badge;
      if (Ansi.stringDisplayWidth(Ansi.stripAnsi(candidate)) > maxWidth) break;
      if (result.isEmpty()) result.append(badge);
      else result.append("  ").append(badge);
    }
    if (!result.isEmpty() && result.length() < plain.length()) {
      result.append("  ").append(Ansi.DIM).append("...").append(Ansi.RESET);
    }
    return result.toString();
  }

  private int termWidth() {
    return Math.max(60, terminal.getSize().getColumns());
  }

  private int transcriptHeight(int termHeight) {
    return Math.max(6, termHeight - 20);
  }

  private int lineCount(String text) {
    if (text == null || text.isEmpty()) return 0;
    return text.split("\n", -1).length;
  }

  private String buildTranscriptBody(int termWidth, int maxLines) {
    var lines = renderTranscriptLines();
    if (lines.isEmpty()) {
      return "Type /help for commands.";
    }

    maxLines = Math.max(1, maxLines);
    int totalLines = lines.size();

    int maxOffset = totalLines <= maxLines
        ? 0
        : Math.max(0, totalLines - Math.max(1, maxLines - 1));
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

    // Scroll-up indicator
    if (start > 0) {
      sb.append(Ansi.DIM).append("↑ ").append(start).append(" more line").append(start != 1 ? "s" : "").append(Ansi.RESET).append("\n");
    }

    int end = Math.min(totalLines, start + maxLines);
    int scrollIndicatorLines = (start > 0 ? 1 : 0);
    int available = maxLines - scrollIndicatorLines;

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

  private List<String> renderTranscriptLines() {
    // Use cached lines if transcript hasn't changed
    if (!transcriptDirty && cachedRenderLines != null) {
      return cachedRenderLines;
    }

    var lines = new ArrayList<String>();
    String separator = Ansi.BLUE + Ansi.DIM + "·" + Ansi.RESET;

    List<TranscriptEntry> snapshot = transcriptSnapshot();

    for (int idx = 0; idx < snapshot.size(); idx++) {
      // Compact separator: just a single line between entries
      if (idx > 0) {
        lines.add(separator);
      }
      var entry = snapshot.get(idx);
      lines.addAll(List.of(renderTranscriptEntry(entry).split("\n")));
    }

    cachedRenderLines = lines;
    transcriptDirty = false;
    return lines;
  }

  private String renderTranscriptEntry(TranscriptEntry entry) {
    return switch (entry) {
      case TranscriptEntry.User u ->
          Ansi.CYAN + Ansi.BOLD + "you" + Ansi.RESET + "\n" + indentBlock(u.body());
      case TranscriptEntry.Assistant a -> {
        String body = a.body();
        // Highlight error messages in red
        boolean isError = body != null
            && (body.startsWith("Error:") || body.startsWith("error:") || body.startsWith("Error\n"));
        String labelColor = isError ? Ansi.RED : Ansi.GREEN;
        yield labelColor + Ansi.BOLD + "assistant" + Ansi.RESET + "\n"
            + indentBlock(MarkdownRenderer.render(body));
      }
      case TranscriptEntry.Progress p ->
          Ansi.YELLOW + Ansi.BOLD + "progress" + Ansi.RESET + "\n" + indentBlock(p.body());
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

        // Collapse long tool output; expand on 'o' key
        String body = t.body();
        if (t.status() != TranscriptEntry.ToolStatus.RUNNING
            && body != null
            && body.length() > TOOL_COLLAPSE_CHARS
            && !expandedTools.contains(t.id())) {
          String[] bodyLines = body.split("\n");
          int lineCount = Math.min(TOOL_PREVIEW_LINES, bodyLines.length);
          var preview = new StringBuilder();
          for (int i = 0; i < lineCount; i++) {
            if (i > 0) preview.append("\n");
            preview.append(bodyLines[i]);
          }
          int totalLines = bodyLines.length;
          int totalChars = body.length();
          if (totalLines > lineCount) {
            preview.append("\n  ").append(Ansi.DIM).append("[").append(totalLines - lineCount)
                .append(" more lines, ").append(totalChars - TOOL_COLLAPSE_CHARS).append(" more chars]")
                .append(Ansi.RESET);
            preview.append("\n  ").append(Ansi.YELLOW).append("[press Ctrl+O to expand]").append(Ansi.RESET);
          }
          body = preview.toString();
        }

        // Apply diff highlighting if body looks like unified diff output
        if (body != null && (body.contains("\n@@") || body.contains("\n--- "))) {
          body = highlightDiff(body);
        }

        yield Ansi.MAGENTA + Ansi.BOLD + "tool" + Ansi.RESET + " " + t.toolName() + " "
            + statusColor + statusLabel + Ansi.RESET + "\n" + indentBlock(body);
      }
    };
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
    String promptLine = Ansi.GREEN + Ansi.BOLD + "mini-code>" + Ansi.RESET;
    String helpText = Ansi.DIM + "Enter send · Esc clear · Ctrl+C exit · Ctrl+O expand" + Ansi.RESET;

    String before = input.substring(0, Math.min(cursorPos, input.length()));
    String at = cursorPos < input.length() ? String.valueOf(input.charAt(cursorPos)) : " ";
    String after = cursorPos < input.length() ? input.substring(cursorPos + 1) : "";

    String placeholder = input.isEmpty()
        ? Ansi.DIM + " Ask for code, files, tasks, or MCP tools" + Ansi.RESET
        : "";

    var inputLine = new StringBuilder();
    inputLine.append(promptLine).append(" ").append(before);
    inputLine.append(Ansi.REVERSE).append(at).append(Ansi.RESET);
    inputLine.append(after);
    inputLine.append(placeholder);

    // Slash menu: show matching commands when input starts with /
    var body = new StringBuilder();
    body.append(helpText).append("\n\n").append(inputLine.toString());

    var visCmds = getVisibleCommands();
    if (!visCmds.isEmpty()) {
      body.append("\n");
      int cmdWidth = Math.max(24, termWidth - 6);
      for (int i = 0; i < visCmds.size(); i++) {
        var cmd = visCmds.get(i);
        String prefix = (i == slashMenuSelectedIndex)
            ? Ansi.REVERSE + "> " + Ansi.RESET
            : "  ";
        String usage = Ansi.BOLD + cmd.usage() + Ansi.RESET;
        String desc = Ansi.DIM + cmd.description() + Ansi.RESET;
        int usageWidth = Ansi.stringDisplayWidth(Ansi.stripAnsi(cmd.usage()));
        int pad = Math.max(1, cmdWidth - usageWidth);
        body.append("\n").append(prefix).append(" ").append(usage);
        body.append(" ".repeat(pad)).append(desc);
      }
    }

    return PanelRenderer.renderPanel("prompt", body.toString(), termWidth);
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

      return PanelRenderer.renderPanel("approval", fb.toString(), termWidth);
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

    return PanelRenderer.renderPanel("approval", sb.toString(), termWidth);
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
        for (int i = 0; i < projects.size(); i++) {
          var p = projects.get(i);
          String prefix = i == sp.projectIndex()
              ? Ansi.REVERSE + "> " + Ansi.RESET
              : "  ";
          sb.append(prefix).append(" ").append(Ansi.BOLD).append(p.cwd()).append(Ansi.RESET);
          sb.append("  ").append(Ansi.DIM).append(p.sessionCount()).append(" sessions").append(Ansi.RESET);
          sb.append("\n");
        }
      }
    } else {
      var sessions = sp.sessions();
      if (sessions.isEmpty()) {
        sb.append(Ansi.DIM).append("(no saved sessions)").append(Ansi.RESET);
      } else {
        for (int i = 0; i < sessions.size(); i++) {
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
      }
    }

    sb.append("\n").append(Ansi.DIM);
    if (sp.allProjects()) {
      sb.append("Up/Down navigate, Tab sessions, Esc cancel");
    } else {
      sb.append("Up/Down navigate, Enter select, d delete, Tab projects, Esc cancel");
    }
    sb.append(Ansi.RESET);

    return PanelRenderer.renderPanel("session picker", sb.toString(), termWidth);
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
      left.append(Ansi.YELLOW).append(Ansi.BOLD).append(statusText).append(Ansi.RESET);
    } else {
      left.append(Ansi.DIM).append("Ready").append(Ansi.RESET);
    }

    var right = new StringBuilder();
    right.append(Ansi.DIM).append("tools").append(Ansi.RESET).append(" ").append(Ansi.GREEN).append("on").append(Ansi.RESET);

    // Show MCP tool count
    if (mcpToolCount > 0) {
      right.append("  ").append(Ansi.DIM).append("mcp").append(Ansi.RESET)
          .append(" ").append(Ansi.BRIGHT_CYAN).append(mcpToolCount).append(Ansi.RESET);
    }

    // Show skills count
    if (skillCount > 0) {
      right.append("  ").append(Ansi.DIM).append("skills").append(Ansi.RESET)
          .append(" ").append(Ansi.MAGENTA).append(skillCount).append(Ansi.RESET);
    }

    // Show running background tasks
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

    int leftLen = Ansi.stringDisplayWidth(Ansi.stripAnsi(left.toString()));
    int rightLen = Ansi.stringDisplayWidth(Ansi.stripAnsi(right.toString()));
    int gap = Math.max(1, termWidth - 2 - leftLen - rightLen);

    return Ansi.BORDER + " " + Ansi.RESET + left + " ".repeat(gap) + right + " " + Ansi.BORDER + Ansi.RESET;
  }
}
