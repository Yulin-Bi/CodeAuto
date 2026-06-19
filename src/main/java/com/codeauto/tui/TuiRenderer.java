package com.codeauto.tui;

import com.codeauto.context.ContextStats;
import com.codeauto.todo.TodoEntry;
import com.codeauto.todo.TodoStore;
import java.io.IOException;
import java.io.Writer;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import org.jline.terminal.Terminal;

/**
 * Terminal rendering for the TUI. Holds {@link Terminal} and {@link Writer}
 * references; reads state from a {@link TuiApp} parameter at render time.
 */
final class TuiRenderer {

  private static final int SCROLL_STEP = 5;
  private static final int SLASH_MENU_MAX_ROWS = 7;
  private static final int LIVE_PROGRESS_MAX_LINES = 5;
  private static final int MIN_TRANSCRIPT_LINES = 1;
  private static final int TODO_ACTIVE_LIMIT = 3;
  private static final int TODO_COMPLETED_LIMIT = 2;

  private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};
  static final String PROGRESS_RUNNING = "RUNNING::";
  static final String PROGRESS_SUCCESS = "SUCCESS::";
  static final String PROGRESS_ERROR = "ERROR::";
  static final String PROGRESS_INFO = "INFO::";

  private final Terminal terminal;
  private final Writer writer;
  private final int contextWindow;

  TuiRenderer(Terminal terminal, Writer writer, int contextWindow) {
    this.terminal = terminal;
    this.writer = writer;
    this.contextWindow = contextWindow;
  }

  // --- Main render entry point ---

  void render(TuiApp app) {
    try {
      renderUnsafe(app);
    } catch (Throwable error) {
      try {
        writer.write(Ansi.CLEAR);
        writer.write(Ansi.HIDE_CURSOR);
        writer.write(PanelRenderer.renderPanel("CodeAuto render error",
            (error.getMessage() == null ? error.toString() : error.getMessage())
                + "\n\nThe TUI is still running. Press Ctrl+C to exit, or continue typing.",
            termWidth()));
        writer.flush();
      } catch (Throwable ignored) {
      }
    }
  }

  private void renderUnsafe(TuiApp app) {
    var sb = new StringBuilder();

    sb.append("\033[H\033[J");
    sb.append(Ansi.HIDE_CURSOR);

    int width = termWidth();
    int height = terminal.getSize().getRows();

    String headerPanel = buildHeaderBodyV2(width, app);
    String todoPanel = buildTodoPanel(width, app);
    boolean useTodoSidebar = shouldUseTodoSidebar(width, todoPanel, app);
    int todoSidebarWidth = useTodoSidebar ? todoSidebarWidth(width) : width;
    int transcriptWidth = useTodoSidebar ? Math.max(60, width - todoSidebarWidth - 3) : width;
    if (useTodoSidebar) {
      todoPanel = buildTodoPanel(todoSidebarWidth, app);
    }

    String bottomPanel;
    if (app.sessionPicker() != null) {
      bottomPanel = renderSessionPickerPanelV2(width, app.sessionPicker());
    } else if (app.pendingApproval() != null) {
      bottomPanel = renderApprovalPanelV2(width, app);
    } else {
      bottomPanel = renderPromptPanelV2(width, app);
    }

    String thinkingBlock = buildThinkingBlock(width, app);
    int thinkingLines = thinkingBlock != null ? lineCount(thinkingBlock) + 1 : 0;
    int topTodoLines = !useTodoSidebar && !todoPanel.isEmpty() ? lineCount(todoPanel) + 1 : 0;
    int transcriptMaxLines = computeTranscriptMaxLines(
        height,
        lineCount(headerPanel),
        topTodoLines,
        lineCount(bottomPanel),
        thinkingLines);
    String footerBar = renderFooterBarV2(width, app);

    String rightTitle = app.transcriptSize() + " events";
    ContextStats stats = app.contextStats();
    if (stats != null) {
      rightTitle += "  ctx " + stats.estimatedTokens() + "  " + colorStatus(stats.warningLevel());
    }
    String transcriptPanel = "";
    String mainContent = "";
    String screenContent = "";
    int mainAreaLines = 0;

    while (true) {
      String transcriptBody = buildTranscriptBody(transcriptWidth, transcriptMaxLines, app);
      transcriptPanel = PanelRenderer.renderFeedPanel("Session Feed", transcriptBody, transcriptWidth, rightTitle);
      mainContent = useTodoSidebar
          ? renderColumns(transcriptPanel, transcriptWidth, todoPanel, todoSidebarWidth, 3)
          : transcriptPanel;
      mainAreaLines = useTodoSidebar
          ? Math.max(lineCount(transcriptPanel), lineCount(todoPanel))
          : lineCount(transcriptPanel);
      screenContent = composeScreenContent(
          width,
          headerPanel,
          useTodoSidebar,
          todoPanel,
          mainContent,
          bottomPanel,
          footerBar,
          thinkingBlock);
      int overflow = lineCount(screenContent) - height;
      if (overflow <= 0 || transcriptMaxLines <= MIN_TRANSCRIPT_LINES) {
        break;
      }
      transcriptMaxLines = Math.max(MIN_TRANSCRIPT_LINES, transcriptMaxLines - overflow);
    }

    sb.append(screenContent);

    sb.append("\033[J");

    if (app.sessionPicker() == null && app.pendingApproval() == null) {
      int promptPanelStartRow = lineCount(headerPanel)
          + topTodoLines
          + 1
          + mainAreaLines
          + 1
          + 1;
      String input = app.inputText();
      int cursorPos = app.cursorPos();
      int safeCursor = Math.max(0, Math.min(cursorPos, input == null ? 0 : input.length()));
      int inputOffset = Ansi.stringDisplayWidth("CodeAuto> ")
          + Ansi.stringDisplayWidth((input == null ? "" : input).substring(0, safeCursor));
      int inputWidth = Math.max(1, width - 4);
      int cursorRow = promptPanelStartRow + 2 + (inputOffset / inputWidth);
      int cursorCol = 3 + (inputOffset % inputWidth);
      sb.append("\033[").append(clampCursorRow(height, cursorRow)).append(";")
          .append(Math.max(1, Math.min(width, cursorCol))).append("H");
    }

    try {
      synchronized (writer) {
        writer.write(sb.toString());
        writer.flush();
      }
    } catch (IOException e) {
    }
  }

  // --- Header ---

  String buildHeaderBody(int termWidth, TuiApp app) {
    var sb = new StringBuilder();
    String cwdName = app.cwd().getFileName().toString();
    String modelName = app.modelName();
    sb.append(Ansi.LIGHT_BLUE).append(Ansi.BOLD).append("CodeAuto").append(Ansi.RESET).append("  ");
    sb.append(Ansi.BLUE).append(Ansi.BOLD).append(Ansi.truncatePlain(cwdName, 24)).append(Ansi.RESET);
    sb.append(" ").append(Ansi.DIM).append(Ansi.truncatePathMiddle(app.cwd().toString(), 40)).append(Ansi.RESET);
    sb.append("\n");

    var badges = new ArrayList<String>();
    badges.add(metric("session", app.sessionId()));
    badges.add(metric("model", modelName));
    badges.add(metric("messages", String.valueOf(app.messageCount())));
    badges.add(metric("tools", String.valueOf(app.toolCount())));
    badges.add(metric("skills", app.skillCount() >= 0 ? String.valueOf(app.skillCount()) : "?"));
    badges.add(renderTodoBadge(app));
    if (app.contextStats() != null) {
      badges.add(renderContextBadge(app.contextStats()));
    }

    var badgeLine = joinBadgeTokens(badges, termWidth);
    sb.append(badgeLine);
    sb.append("\n");
    sb.append(Ansi.DARK_GRAY).append("─".repeat(Math.max(0, termWidth))).append(Ansi.RESET);

    return sb.toString();
  }

  String buildHeaderBodyV2(int termWidth, TuiApp app) {
    var sb = new StringBuilder();
    String cwdName = app.cwd().getFileName().toString();
    String modelName = app.modelName();

    sb.append(Ansi.LIGHT_BLUE).append(Ansi.BOLD).append("CodeAuto").append(Ansi.RESET);
    sb.append("  ").append(Ansi.BOLD).append(Ansi.truncatePlain(cwdName, 28)).append(Ansi.RESET);
    sb.append("\n");
    sb.append(Ansi.DIM).append(Ansi.truncatePathMiddle(app.cwd().toString(), Math.max(24, termWidth))).append(Ansi.RESET);
    sb.append("\n");

    var primary = new ArrayList<String>();
    primary.add(metric("session", app.sessionId()));
    primary.add(metric("model", modelName));
    sb.append(joinBadgeTokens(primary, termWidth));
    sb.append("\n");

    var secondary = new ArrayList<String>();
    secondary.add(metric("messages", String.valueOf(app.messageCount())));
    secondary.add(metric("tools", String.valueOf(app.toolCount())));
    secondary.add(metric("skills", app.skillCount() >= 0 ? String.valueOf(app.skillCount()) : "?"));
    if (app.contextStats() != null) {
      secondary.add(renderContextBadge(app.contextStats()));
    }
    sb.append(joinBadgeTokens(secondary, termWidth));
    return sb.toString();
  }

  private String buildTodoPanel(int termWidth, TuiApp app) {
    try {
      var todos = new TodoStore(app.cwd()).list(null);
      String body = renderTodoPanelBody(todos, termWidth);
      if (body.isBlank()) {
        return "";
      }
      return PanelRenderer.renderFeedPanel("ToDo", body, termWidth, null);
    } catch (Exception ignored) {
      return "";
    }
  }

  private boolean shouldUseTodoSidebar(int termWidth, String todoPanel, TuiApp app) {
    return todoPanel != null
        && !todoPanel.isBlank()
        && termWidth >= 120
        && app.sessionPicker() == null
        && app.pendingApproval() == null;
  }

  private int todoSidebarWidth(int termWidth) {
    return Math.min(38, Math.max(28, termWidth / 4));
  }

  // --- Transcript ---

  private String buildTranscriptBody(int termWidth, int maxLines, TuiApp app) {
    var pinnedProgress = buildPinnedProgressLines(termWidth, app);
    int pinnedLines = pinnedProgress.size();
    int transcriptBudget = Math.max(1, maxLines - pinnedLines);
    var lines = wrapDisplayLines(renderTranscriptLines(app), Math.max(1, termWidth - 4));
    if (lines.isEmpty()) {
      lines = List.of("Type /help for commands.");
    }

    transcriptBudget = Math.max(1, transcriptBudget);
    int totalLines = lines.size();

    int maxOffset = totalLines <= transcriptBudget
        ? 0
        : Math.max(0, totalLines - Math.max(1, transcriptBudget - 1));
    if (app.transcriptAutoScroll()) {
      app.setTranscriptScrollOffset(maxOffset);
    }

    int offset = app.transcriptScrollOffset();
    offset = Math.max(0, Math.min(offset, maxOffset));
    app.setTranscriptScrollOffset(offset);

    if (!app.transcriptAutoScroll() && offset >= maxOffset) {
      app.setTranscriptAutoScroll(true);
    }

    int start = offset;
    var sb = new StringBuilder();
    if (!pinnedProgress.isEmpty()) {
      sb.append(String.join("\n", pinnedProgress));
      if (transcriptBudget > 0) sb.append("\n");
    }

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

      if (actualEnd < totalLines) {
        int hidden = totalLines - actualEnd;
        if (sb.length() > 0) sb.append("\n");
        sb.append(Ansi.DIM).append("↓ ").append(hidden).append(" more line").append(hidden != 1 ? "s" : "").append(Ansi.RESET);
      }
    }

    return sb.toString();
  }

  private List<String> buildPinnedProgressLines(int termWidth, TuiApp app) {
    if (!app.isBusy() || app.progressTraceEntryId() == null) return List.of();
    String body = app.turnProgressTraceSnapshot();
    if (body == null) return List.of();

    int id = app.progressTraceEntryId() == null ? -1 : app.progressTraceEntryId();
    var progress = new TranscriptEntry.Progress(id, body);
    var lines = new ArrayList<String>();
    lines.add(Ansi.GRAY + "┄".repeat(Math.max(12, Math.min(72, termWidth - 8))) + Ansi.RESET);
    lines.add(Ansi.YELLOW + SPINNER_FRAMES[app.spinnerFrame()] + " " + Ansi.BOLD + "progress" + Ansi.RESET);
    for (String line : renderProgressBody(progress, app).split("\n", -1)) {
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

  List<String> renderTranscriptLines(TuiApp app) {
    if (!app.hasPinnedProgress() && !app.isTranscriptDirty() && app.cachedRenderLines() != null) {
      return app.cachedRenderLines();
    }

    var lines = new ArrayList<String>();
    List<TranscriptEntry> snapshot = app.transcriptSnapshot();

    int idx = 0;
    while (idx < snapshot.size()) {
      var entry = snapshot.get(idx);
      if (app.hasPinnedProgress()
          && app.progressTraceEntryId() != null
          && entry instanceof TranscriptEntry.Progress p
          && p.id() == app.progressTraceEntryId()) {
        idx++;
        continue;
      }

      if (isActivityEntry(entry)) {
        int start = idx;
        while (idx < snapshot.size()) {
          var candidate = snapshot.get(idx);
          if (app.hasPinnedProgress()
              && app.progressTraceEntryId() != null
              && candidate instanceof TranscriptEntry.Progress progress
              && progress.id() == app.progressTraceEntryId()) {
            idx++;
            continue;
          }
          if (!isActivityEntry(candidate)) {
            break;
          }
          idx++;
        }
        if (!lines.isEmpty()) {
          lines.add("");
        }
        lines.addAll(List.of(renderActivityGroup(snapshot.subList(start, idx), app).split("\n")));
        continue;
      }

      if (!lines.isEmpty()) {
        lines.add("");
      }
      lines.addAll(List.of(renderTranscriptCard(entry, app).split("\n")));
      idx++;
    }

    if (!app.hasPinnedProgress()) {
      app.setCachedRenderLines(lines);
      app.setTranscriptDirty(false);
    }
    return lines;
  }

  private boolean isActivityEntry(TranscriptEntry entry) {
    return entry instanceof TranscriptEntry.Tool
        || entry instanceof TranscriptEntry.Progress
        || entry instanceof TranscriptEntry.Status;
  }

  // --- Prompt ---

  private String renderPromptPanel(int termWidth, TuiApp app) {
    String promptLine = Ansi.LIGHT_BLUE + Ansi.BOLD + "CodeAuto>" + Ansi.RESET;

    String currentInput = app.inputText();
    int cursorPos = app.cursorPos();
    int safeCursor = Math.max(0, Math.min(cursorPos, currentInput.length()));
    String before = currentInput.substring(0, safeCursor);
    String at = safeCursor < currentInput.length() ? String.valueOf(currentInput.charAt(safeCursor)) : " ";
    String after = safeCursor < currentInput.length() ? currentInput.substring(safeCursor + 1) : "";

    String placeholder = currentInput.isEmpty()
        ? Ansi.DIM + " Ask for code, files, tasks, or MCP tools" + Ansi.RESET
        : "";

    var inputLine = new StringBuilder();
    inputLine.append(promptLine).append(" ").append(before);
    if (app.cursorBlinkVisible()) {
      inputLine.append(Ansi.REVERSE).append(at).append(Ansi.RESET);
    } else {
      inputLine.append(at);
    }
    inputLine.append(after);
    inputLine.append(placeholder);

    var body = new StringBuilder();
    body.append(Ansi.DARK_GRAY).append("─".repeat(Math.max(0, termWidth))).append(Ansi.RESET)
        .append("\n")
        .append(inputLine)
        .append("\n")
        .append(Ansi.DIM)
        .append("Enter send | Esc clear | Ctrl+C interrupt/exit | Ctrl+O progress | Ctrl+↑/↓ scroll")
        .append(Ansi.RESET);

    var visCmds = app.getVisibleCommands();
    if (!visCmds.isEmpty()) {
      body.append("\n");
      int cmdWidth = Math.max(24, termWidth - 6);
      int selected = Math.max(0, Math.min(app.slashMenuSelectedIndex(), visCmds.size() - 1));
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

  private String renderPromptPanelV2(int termWidth, TuiApp app) {
    String promptLine = Ansi.LIGHT_BLUE + Ansi.BOLD + "CodeAuto>" + Ansi.RESET;

    String currentInput = app.inputText();
    int cursorPos = app.cursorPos();
    int safeCursor = Math.max(0, Math.min(cursorPos, currentInput.length()));
    String before = currentInput.substring(0, safeCursor);
    String at = safeCursor < currentInput.length() ? String.valueOf(currentInput.charAt(safeCursor)) : " ";
    String after = safeCursor < currentInput.length() ? currentInput.substring(safeCursor + 1) : "";

    String placeholder = currentInput.isEmpty()
        ? Ansi.DIM + " Ask CodeAuto to inspect, edit, or explain." + Ansi.RESET
        : "";

    var inputLine = new StringBuilder();
    inputLine.append(promptLine).append(" ").append(before);
    if (app.cursorBlinkVisible()) {
      inputLine.append(Ansi.REVERSE).append(at).append(Ansi.RESET);
    } else {
      inputLine.append(at);
    }
    inputLine.append(after);
    inputLine.append(placeholder);

    var body = new StringBuilder();
    var wrappedInput = wrapDisplayLines(List.of(inputLine.toString()), Math.max(1, termWidth));
    body.append(String.join("\n", wrappedInput)).append("\n");
    body.append(Ansi.DIM)
        .append("Enter send  Esc clear  PgUp/PgDn or wheel scroll  Ctrl+O progress")
        .append(Ansi.RESET);

    var visCmds = app.getVisibleCommands();
    if (!visCmds.isEmpty()) {
      body.append("\n\n");
      body.append(Ansi.GRAY).append("Commands").append(Ansi.RESET);
      int cmdWidth = Math.max(24, termWidth - 10);
      int selected = Math.max(0, Math.min(app.slashMenuSelectedIndex(), visCmds.size() - 1));
      int start = Math.max(0, selected - SLASH_MENU_MAX_ROWS / 2);
      int end = Math.min(visCmds.size(), start + SLASH_MENU_MAX_ROWS);
      start = Math.max(0, end - SLASH_MENU_MAX_ROWS);
      for (int i = start; i < end; i++) {
        var cmd = visCmds.get(i);
        boolean isSelected = i == selected;
        String prefix = isSelected
            ? Ansi.BRIGHT_CYAN + Ansi.BOLD + "> " + Ansi.RESET
            : Ansi.DIM + "  " + Ansi.RESET;
        String usage = (isSelected ? Ansi.BRIGHT_CYAN : "") + Ansi.BOLD + cmd.usage() + Ansi.RESET;
        String desc = Ansi.DIM + cmd.description() + Ansi.RESET;
        int usageWidth = Ansi.stringDisplayWidth(Ansi.stripAnsi(cmd.usage()));
        int pad = Math.max(1, cmdWidth - usageWidth);
        body.append("\n").append(prefix).append(usage).append(" ".repeat(pad)).append(desc);
      }
      if (visCmds.size() > SLASH_MENU_MAX_ROWS) {
        body.append("\n")
            .append(Ansi.DIM)
            .append(selected + 1)
            .append("/")
            .append(visCmds.size())
            .append(" commands")
            .append(Ansi.RESET);
      }
    }

    return PanelRenderer.renderFeedPanel("Compose", body.toString(), termWidth, null);
  }

  // --- Approval dialog ---

  private String renderApprovalPanel(int termWidth, TuiApp app) {
    var pa = app.pendingApproval();
    if (pa == null) return "";

    if (app.approvalFeedbackMode()) {
      var fb = new StringBuilder();
      fb.append(Ansi.YELLOW).append(Ansi.BOLD).append("Reject With Guidance").append(Ansi.RESET).append("\n");
      fb.append(Ansi.DIM).append("Type feedback for model, Enter submit, Esc back").append(Ansi.RESET).append("\n\n");

      String fbText = app.approvalFeedbackText();
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

  private String renderApprovalPanelV2(int termWidth, TuiApp app) {
    var pa = app.pendingApproval();
    if (pa == null) return "";

    if (app.approvalFeedbackMode()) {
      var fb = new StringBuilder();
      fb.append(Ansi.YELLOW).append(Ansi.BOLD).append("Reject with guidance").append(Ansi.RESET).append("\n");
      fb.append(Ansi.DIM).append("Enter submit  Esc back").append(Ansi.RESET).append("\n\n");
      fb.append(Ansi.BOLD).append("feedback>").append(Ansi.RESET).append(" ");
      fb.append(app.approvalFeedbackText());
      fb.append(Ansi.REVERSE).append(" ").append(Ansi.RESET);
      return PanelRenderer.renderFeedPanel("Approval", fb.toString(), termWidth, null);
    }

    var req = pa.request();
    var sb = new StringBuilder();
    sb.append(Ansi.YELLOW).append(Ansi.BOLD).append("Approval Required").append(Ansi.RESET).append("\n");
    sb.append(req.summary()).append("\n");
    sb.append(Ansi.DIM).append(req.scope()).append(Ansi.RESET).append("\n\n");

    var choices = req.choices();
    for (int i = 0; i < choices.size(); i++) {
      String label = switch (choices.get(i)) {
        case ALLOW_ONCE -> "Allow once";
        case ALLOW_ALWAYS -> "Allow always";
        case ALLOW_TURN -> "Allow this turn";
        case ALLOW_ALL_TURN -> "Allow all this turn";
        case DENY_ONCE -> "Deny";
        case DENY_ALWAYS -> "Deny always";
        case DENY_WITH_FEEDBACK -> "Deny with guidance";
      };
      boolean isSelected = i == pa.selectedIndex();
      String prefix = isSelected
          ? Ansi.YELLOW + Ansi.BOLD + "> " + Ansi.RESET
          : Ansi.DIM + "  " + Ansi.RESET;
      sb.append(prefix).append(isSelected ? Ansi.BOLD + label + Ansi.RESET : label);
      if (i < choices.size() - 1) sb.append("\n");
    }

    sb.append("\n\n").append(Ansi.DIM)
        .append("Up/Down move  Enter confirm  Esc deny  y/n or 1-7 shortcuts")
        .append(Ansi.RESET);
    return PanelRenderer.renderFeedPanel("Approval", sb.toString(), termWidth, null);
  }

  // --- Session picker ---

  private String renderSessionPickerPanel(int termWidth, TuiApp.SessionPickerState sp) {
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

  private String renderSessionPickerPanelV2(int termWidth, TuiApp.SessionPickerState sp) {
    if (sp == null) return "";

    var sb = new StringBuilder();
    if (sp.allProjects()) {
      sb.append(Ansi.GRAY).append("Projects").append(Ansi.RESET).append("\n\n");
      var projects = sp.projects();
      if (projects.isEmpty()) {
        sb.append(Ansi.DIM).append("(no other projects found)").append(Ansi.RESET);
      } else {
        int start = Math.max(0, Math.min(sp.projectIndex() - 3, Math.max(0, projects.size() - 7)));
        int end = Math.min(projects.size(), start + 7);
        if (start > 0) sb.append(Ansi.DIM).append("... ").append(start).append(" more projects").append(Ansi.RESET).append("\n");
        for (int i = start; i < end; i++) {
          var p = projects.get(i);
          boolean isSelected = i == sp.projectIndex();
          String prefix = isSelected
              ? Ansi.BRIGHT_CYAN + Ansi.BOLD + "> " + Ansi.RESET
              : Ansi.DIM + "  " + Ansi.RESET;
          sb.append(prefix)
              .append(isSelected ? Ansi.BRIGHT_CYAN : "")
              .append(Ansi.BOLD).append(p.cwd()).append(Ansi.RESET);
          sb.append("  ").append(Ansi.DIM).append(p.sessionCount()).append(" sessions").append(Ansi.RESET);
          if (i < end - 1) sb.append("\n");
        }
      }
      sb.append("\n\n").append(Ansi.DIM).append("Up/Down move  Tab sessions  Esc cancel").append(Ansi.RESET);
      return PanelRenderer.renderFeedPanel("Projects", sb.toString(), termWidth, null);
    }

    sb.append(Ansi.GRAY).append("Sessions").append(Ansi.RESET).append("\n\n");
    var sessions = sp.sessions();
    if (sessions.isEmpty()) {
      sb.append(Ansi.DIM).append("(no saved sessions)").append(Ansi.RESET);
    } else {
      int start = Math.max(0, Math.min(sp.selectedIndex() - 3, Math.max(0, sessions.size() - 7)));
      int end = Math.min(sessions.size(), start + 7);
      if (start > 0) sb.append(Ansi.DIM).append("... ").append(start).append(" more sessions").append(Ansi.RESET).append("\n");
      for (int i = start; i < end; i++) {
        var s = sessions.get(i);
        boolean isSelected = i == sp.selectedIndex();
        String prefix = isSelected
            ? Ansi.BRIGHT_CYAN + Ansi.BOLD + "> " + Ansi.RESET
            : Ansi.DIM + "  " + Ansi.RESET;
        sb.append(prefix)
            .append(isSelected ? Ansi.BRIGHT_CYAN : "")
            .append(Ansi.BOLD).append(Ansi.truncatePlain(s.title(), 50)).append(Ansi.RESET);
        if (sp.deleteConfirmIndex() == i) {
          sb.append("  ").append(Ansi.YELLOW).append("[press d again to delete]").append(Ansi.RESET);
        }
        if (i < end - 1) sb.append("\n\n");
      }
    }

    sb.append("\n\n").append(Ansi.DIM)
        .append("Up/Down move  Enter select  d delete  Tab projects  Esc cancel")
        .append(Ansi.RESET);
    return PanelRenderer.renderFeedPanel("Sessions", sb.toString(), termWidth, null);
  }

  // --- Footer ---

  private String buildThinkingBlock(int width, TuiApp app) {
    String thinking = app.thinkingText();
    if (thinking == null || thinking.isBlank()) return null;
    String[] lines = thinking.split("\n");
    int start = Math.max(0, lines.length - 3);
    var sb = new StringBuilder();
    for (int i = start; i < lines.length; i++) {
      String line = lines[i];
      if (line.length() > width - 10) {
        line = line.substring(0, Math.max(0, width - 13)) + "...";
      }
      sb.append(Ansi.YELLOW).append("  think: ").append(line).append(Ansi.RESET);
      if (i < lines.length - 1) sb.append("\n");
    }
    return sb.toString();
  }

  private String renderFooterBar(int termWidth, TuiApp app) {
    return renderFooterBarV2(termWidth, app);
  }

  /*
  private String renderFooterBarLegacy(int termWidth, TuiApp app) {
    var left = new ArrayList<String>();
    String statusText = app.statusText();
    int spinnerFrame = app.spinnerFrame();
    if (statusText != null) {
      left.add(Ansi.YELLOW + Ansi.BOLD + SPINNER_FRAMES[spinnerFrame] + " "
          + app.statusWithElapsed() + Ansi.RESET);
    } else {
      left.add(Ansi.DIM + "ready" + Ansi.RESET);
    }

    var right = new ArrayList<String>();
    var bgTasks = com.codeauto.background.BackgroundTaskRegistry.get().list();
    long runningCount = bgTasks.stream().filter(t -> "running".equals(t.status())).count();
    if (runningCount > 0) {
      right.add(metric("shells", String.valueOf(runningCount), Ansi.BRIGHT_CYAN));
    }

    if (app.transcriptScrollOffset() > 0) {
      left.append("  ").append(Ansi.DIM).append("↑ scroll").append(Ansi.RESET);
    }

    String compactNotification = app.compactNotification();
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

  */

  private String renderFooterBarV2(int termWidth, TuiApp app) {
    var left = new ArrayList<String>();
    String statusText = app.statusText();
    int spinnerFrame = app.spinnerFrame();
    if (statusText != null) {
      left.add(Ansi.YELLOW + Ansi.BOLD + SPINNER_FRAMES[spinnerFrame] + " "
          + app.statusWithElapsed() + Ansi.RESET);
    } else {
      left.add(Ansi.DIM + "ready" + Ansi.RESET);
    }

    if (app.transcriptScrollOffset() > 0) {
      left.add(Ansi.DIM + "scroll lock" + Ansi.RESET);
    }

    var right = new ArrayList<String>();
    var bgTasks = com.codeauto.background.BackgroundTaskRegistry.get().list();
    long runningCount = bgTasks.stream().filter(t -> "running".equals(t.status())).count();
    if (runningCount > 0) {
      right.add(metric("shells", String.valueOf(runningCount), Ansi.BRIGHT_CYAN));
    }

    String compactNotification = app.compactNotification();
    if (compactNotification != null) {
      right.add(metric("compact", compactNotification, Ansi.YELLOW));
    }

    int contentWidth = Math.max(1, termWidth - 2);
    String leftText = String.join("  ", left);
    String rightText = String.join(" ", right);
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

  // --- Entry rendering ---

  private String renderTranscriptCard(TranscriptEntry entry, TuiApp app) {
    return switch (entry) {
      case TranscriptEntry.User u ->
          renderUserTranscriptBlock(u.body());
      case TranscriptEntry.Assistant a -> {
        String body = a.body();
        boolean isError = body != null
            && (body.startsWith("Error:") || body.startsWith("error:") || body.startsWith("Error\n"));
        yield renderAssistantTranscriptBlock(MarkdownRenderer.render(body), isError);
      }
      case TranscriptEntry.Status s ->
          Ansi.YELLOW + s.body() + Ansi.RESET;
      case TranscriptEntry.Progress p ->
          renderActivityBlock(
              Ansi.YELLOW + "progress" + Ansi.RESET + progressToggleHint(p.id(), app.expandedProgressContains(p.id())),
              renderProgressBody(p, app));
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
        yield renderActivityBlock(
            Ansi.MAGENTA + "tool" + Ansi.RESET + " " + t.toolName() + " "
                + statusColor + statusLabel + Ansi.RESET,
            t.body());
      }
    };
  }

  private String renderActivityGroup(List<TranscriptEntry> entries, TuiApp app) {
    var lines = new ArrayList<String>();
    int toolCount = 0;
    int progressCount = 0;
    int errorCount = 0;

    for (TranscriptEntry entry : entries) {
      switch (entry) {
        case TranscriptEntry.Tool t -> {
          toolCount++;
          if (t.status() == TranscriptEntry.ToolStatus.ERROR) {
            errorCount++;
          }
          String status = switch (t.status()) {
            case RUNNING -> Ansi.YELLOW + "running" + Ansi.RESET;
            case SUCCESS -> Ansi.GREEN + "ok" + Ansi.RESET;
            case ERROR -> Ansi.RED + "err" + Ansi.RESET;
          };
          lines.add(Ansi.MAGENTA + "tool" + Ansi.RESET + " " + t.toolName() + " " + status);
        }
        case TranscriptEntry.Progress p -> {
          progressCount++;
          lines.add(Ansi.YELLOW + "progress" + Ansi.RESET
              + progressToggleHint(p.id(), app.expandedProgressContains(p.id())));
          for (String line : renderProgressBody(p, app).split("\n", -1)) {
            if (!line.isBlank()) {
              lines.add(Ansi.DIM + "  " + line + Ansi.RESET);
            }
          }
        }
        case TranscriptEntry.Status s -> lines.add(Ansi.YELLOW + s.body() + Ansi.RESET);
        default -> {
        }
      }
    }

    String summary = Ansi.DIM + "activity" + Ansi.RESET + " "
        + Ansi.BOLD + entries.size() + Ansi.RESET + " events";
    if (toolCount > 0) {
      summary += Ansi.DIM + "  tools " + toolCount + Ansi.RESET;
    }
    if (progressCount > 0) {
      summary += Ansi.DIM + "  progress " + progressCount + Ansi.RESET;
    }
    if (errorCount > 0) {
      summary += " " + Ansi.RED + "errors " + errorCount + Ansi.RESET;
    }

    return renderActivityBlock(summary, String.join("\n", lines));
  }

  static String renderTranscriptBlock(String title, String body) {
    var sb = new StringBuilder();
    sb.append(title == null ? "" : title);
    if (body == null || body.isBlank()) {
      return sb.toString();
    }
    for (String line : body.split("\n", -1)) {
      sb.append("\n")
          .append(Ansi.DARK_GRAY).append("|").append(Ansi.RESET)
          .append(" ")
          .append(line);
    }
    return sb.toString();
  }

  static String renderUserTranscriptBlock(String body) {
    var sb = new StringBuilder();
    if (body == null || body.isBlank()) {
      return Ansi.USER_EDGE + Ansi.BOLD + ">" + Ansi.RESET;
    }
    boolean first = true;
    for (String line : body.split("\n", -1)) {
      if (!first) {
        sb.append("\n");
      }
      sb.append(Ansi.USER_EDGE).append(Ansi.BOLD).append(">").append(Ansi.RESET)
          .append(" ")
          .append(Ansi.USER_BG).append(" ").append(line).append(" ").append(Ansi.RESET);
      first = false;
    }
    return sb.toString();
  }

  static String renderAssistantTranscriptBlock(String body, boolean isError) {
    String marker = isError ? "!" : ">";
    String markerColor = isError ? Ansi.RED : Ansi.BOLD;
    var sb = new StringBuilder();
    if (body == null || body.isBlank()) {
      return markerColor + Ansi.BOLD + marker + Ansi.RESET;
    }
    String[] lines = body.split("\n", -1);
    sb.append(markerColor).append(Ansi.BOLD).append(marker).append(Ansi.RESET)
        .append(" ")
        .append(lines[0]);
    for (int i = 1; i < lines.length; i++) {
      sb.append("\n")
          .append(Ansi.DARK_GRAY).append("|").append(Ansi.RESET)
          .append(" ")
          .append(lines[i]);
    }
    return sb.toString();
  }

  static String renderActivityBlock(String title, String body) {
    var sb = new StringBuilder();
    sb.append(title == null ? "" : title);
    if (body == null || body.isBlank()) {
      return sb.toString();
    }
    for (String line : body.split("\n", -1)) {
      sb.append("\n")
          .append(Ansi.DIM).append("> ").append(Ansi.RESET)
          .append(Ansi.DIM).append(line).append(Ansi.RESET);
    }
    return sb.toString();
  }

  private String renderTranscriptEntry(TranscriptEntry entry, TuiApp app) {
    return switch (entry) {
      case TranscriptEntry.User u ->
          Ansi.GRAY + "┄".repeat(Math.max(12, Math.min(72, termWidth() - 8))) + Ansi.RESET + "\n"
              + Ansi.CYAN + Ansi.BOLD + "you" + Ansi.RESET + "\n" + indentBlock(u.body());
      case TranscriptEntry.Assistant a -> {
        String body = a.body();
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
              + progressToggleHint(p.id(), app.expandedProgressContains(p.id()))
              + "\n" + indentBlock(renderProgressBody(p, app));
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

  // --- Progress rendering ---

  private String progressToggleHint(int id, boolean expanded) {
    return Ansi.DIM + (expanded ? " [-]" : " [+]") + Ansi.RESET;
  }

  private String renderProgressBody(TranscriptEntry.Progress progress, TuiApp app) {
    String body = progress.body() == null ? "" : progress.body();
    List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));
    lines.removeIf(String::isBlank);
    lines.replaceAll(this::renderProgressLine);
    boolean expanded = app != null && app.expandedProgressContains(progress.id());
    if (expanded || lines.size() <= 4) {
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
      return SPINNER_FRAMES[0] + " " + raw.substring(PROGRESS_RUNNING.length());
    }
    if (raw.startsWith(PROGRESS_SUCCESS)) {
      return "[OK] " + raw.substring(PROGRESS_SUCCESS.length());
    }
    if (raw.startsWith(PROGRESS_ERROR)) {
      return "[ERR] " + raw.substring(PROGRESS_ERROR.length());
    }
    if (raw.startsWith(PROGRESS_INFO)) {
      return "- " + raw.substring(PROGRESS_INFO.length());
    }
    return raw;
  }

  // --- Pure helpers ---

  static String indentBlock(String input) {
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

  static String horizontalRule(int width) {
    return Ansi.DARK_GRAY + "─".repeat(Math.max(0, width - 1)) + Ansi.RESET;
  }

  static int lineCount(String text) {
    if (text == null || text.isEmpty()) return 0;
    return text.split("\n", -1).length;
  }

  static String metric(String label, String value) {
    return metric(label, value, "");
  }

  static String metric(String label, String value, String valueColor) {
    String safeValue = value == null || value.isBlank() ? "-" : value;
    String color = valueColor == null ? "" : valueColor;
    return Ansi.DARK_GRAY + "[" + Ansi.RESET
        + Ansi.GRAY + label + Ansi.RESET
        + Ansi.DARK_GRAY + ": " + Ansi.RESET
        + color + Ansi.BOLD + safeValue + Ansi.RESET
        + Ansi.DARK_GRAY + "]" + Ansi.RESET;
  }

  static String colorStatus(String level) {
    String normalized = level == null || level.isBlank() ? "ok" : level;
    String color = switch (normalized) {
      case "warning" -> Ansi.YELLOW;
      case "critical" -> Ansi.RED;
      case "blocked" -> Ansi.BRIGHT_RED;
      default -> Ansi.GREEN;
    };
    return color + normalized.toUpperCase() + Ansi.RESET;
  }

  private String renderContextBadge(ContextStats stats) {
    if (stats == null) return "";
    int pct = Math.min(100, Math.max(0,
        (int) ((double) stats.estimatedTokens() / contextWindow * 100)));
    String color = switch (stats.warningLevel()) {
      case "warning" -> Ansi.YELLOW;
      case "critical" -> Ansi.RED;
      case "blocked" -> Ansi.BRIGHT_RED;
      default -> Ansi.GREEN;
    };
    return metric("ctx", pct + "%", color);
  }

  private String renderTodoBadge(TuiApp app) {
    try {
      TodoStore store = new TodoStore(app.cwd());
      var groups = store.recentActiveGroups();
      if (groups.isEmpty()) {
        long completed = store.groups().stream().mapToLong(TodoStore.TodoGroup::completedCount).sum();
        if (completed > 0) {
          return metric("todos", "done", Ansi.GREEN);
        }
        return "";
      }
      long activeTodos = groups.stream()
          .mapToLong(group -> group.pendingCount() + group.inProgressCount())
          .sum();
      return metric("todos", groups.size() + "g " + activeTodos + "t", Ansi.YELLOW);
    } catch (Exception ignored) {
      return "";
    }
  }

  static String renderTodoSnapshot(List<TodoEntry> todos) {
    if (todos == null || todos.isEmpty()) {
      return "";
    }

    var groups = TodoStore.groupTodos(todos).stream()
        .filter(TodoStore.TodoGroup::hasActiveItems)
        .limit(2)
        .toList();
    if (!groups.isEmpty()) {
      var lines = new ArrayList<String>();
      boolean firstGroup = true;
      for (var group : groups) {
        if (!firstGroup) {
          lines.add("");
        }
        lines.add(Ansi.BOLD + group.title() + Ansi.RESET);

        var activeEntries = group.entries().stream()
            .filter(todo -> "in_progress".equals(todo.status()) || "pending".equals(todo.status()))
            .toList();
        int shownActive = 0;
        for (TodoEntry todo : activeEntries) {
          if (shownActive >= TODO_ACTIVE_LIMIT) {
            break;
          }
          String marker = "in_progress".equals(todo.status())
              ? Ansi.YELLOW + "▣" + Ansi.RESET
              : Ansi.DIM + "☐" + Ansi.RESET;
          lines.add(marker + " " + todoText(todo));
          shownActive++;
        }
        if (activeEntries.size() > shownActive) {
          lines.add(Ansi.DIM + "还有 " + (activeEntries.size() - shownActive) + " 项未完成" + Ansi.RESET);
        }

        var completedEntries = group.entries().stream()
            .filter(todo -> "completed".equals(todo.status()))
            .toList();
        int shownCompleted = 0;
        for (TodoEntry todo : completedEntries) {
          if (shownCompleted >= TODO_COMPLETED_LIMIT) {
            break;
          }
          lines.add(Ansi.GREEN + "☑" + Ansi.RESET + " " + todo.content());
          shownCompleted++;
        }
        if (completedEntries.size() > shownCompleted) {
          lines.add(Ansi.DIM + "还有 " + (completedEntries.size() - shownCompleted) + " 项已完成" + Ansi.RESET);
        }
        firstGroup = false;
      }
      return String.join("\n", lines);
    }

    var active = todos.stream()
        .filter(t -> "in_progress".equals(t.status()) || "pending".equals(t.status()))
        .sorted(java.util.Comparator.comparingInt(TuiRenderer::todoPriority)
            .thenComparing(TodoEntry::createdAt)
            .thenComparing(TodoEntry::updatedAt, java.util.Comparator.reverseOrder()))
        .toList();
    var completed = todos.stream()
        .filter(t -> "completed".equals(t.status()))
        .sorted(java.util.Comparator.comparing(TodoEntry::updatedAt, java.util.Comparator.reverseOrder()))
        .toList();
    if (active.isEmpty()) {
      return "";
    }

    var lines = new ArrayList<String>();

    lines.add(Ansi.BOLD + "未完成" + Ansi.RESET);
    int shownActive = 0;
    for (TodoEntry todo : active) {
      if (shownActive >= TODO_ACTIVE_LIMIT) {
        break;
      }
      String text = todoText(todo);
      String marker = "in_progress".equals(todo.status())
          ? Ansi.YELLOW + "☐" + Ansi.RESET
          : Ansi.DIM + "☐" + Ansi.RESET;
      lines.add(marker + " " + Ansi.truncatePlain(text, 72));
      shownActive++;
    }
    if (active.size() > shownActive) {
      lines.add(Ansi.DIM + "还有 " + (active.size() - shownActive) + " 项未完成" + Ansi.RESET);
    }

    if (!completed.isEmpty()) {
      lines.add("");
      lines.add(Ansi.BOLD + "已完成" + Ansi.RESET);
      int shownCompleted = 0;
      for (TodoEntry todo : completed) {
        if (shownCompleted >= TODO_COMPLETED_LIMIT) {
          break;
        }
        lines.add(Ansi.GREEN + "☑" + Ansi.RESET + " " + Ansi.truncatePlain(todo.content(), 72));
        shownCompleted++;
      }
      if (completed.size() > shownCompleted) {
        lines.add(Ansi.DIM + "还有 " + (completed.size() - shownCompleted) + " 项已完成" + Ansi.RESET);
      }
    }
    return String.join("\n", lines);
  }

  static String renderTodoPanelBody(List<TodoEntry> todos, int termWidth) {
    String body = renderTodoSnapshot(todos);
    if (body == null || body.isBlank()) {
      return "";
    }
    return String.join("\n", wrapDisplayLines(List.of(body.split("\n", -1)), Math.max(8, termWidth - 1)));
  }

  private static int todoPriority(TodoEntry entry) {
    return switch (entry.status()) {
      case "in_progress" -> 0;
      case "pending" -> 1;
      default -> 2;
    };
  }

  private static String todoText(TodoEntry todo) {
    if (todo == null) {
      return "";
    }
    if ("in_progress".equals(todo.status())
        && todo.activeForm() != null
        && !todo.activeForm().isBlank()) {
      return todo.activeForm();
    }
    return todo.content() == null ? "" : todo.content();
  }

  static int computeTranscriptMaxLines(
      int termHeight,
      int headerLines,
      int topTodoLines,
      int bottomLines,
      int thinkingLines) {
    int fixedLines = headerLines
        + topTodoLines
        + 1
        + 1
        + 1
        + bottomLines
        + 1
        + thinkingLines;
    int transcriptPanelOverhead = 1;
    return Math.max(MIN_TRANSCRIPT_LINES, termHeight - fixedLines - transcriptPanelOverhead);
  }

  static int clampCursorRow(int termHeight, int cursorRow) {
    return Math.max(1, Math.min(termHeight, cursorRow));
  }

  static String composeScreenContent(
      int width,
      String headerPanel,
      boolean useTodoSidebar,
      String todoPanel,
      String mainContent,
      String bottomPanel,
      String footerBar,
      String thinkingBlock) {
    var sb = new StringBuilder();
    sb.append(headerPanel).append("\n");
    if (!useTodoSidebar && todoPanel != null && !todoPanel.isEmpty()) {
      sb.append(todoPanel).append("\n\n");
    }
    sb.append(horizontalRule(width)).append("\n");
    sb.append(mainContent).append("\n\n");
    sb.append(horizontalRule(width)).append("\n");
    sb.append(bottomPanel);
    sb.append("\n").append(footerBar);
    if (thinkingBlock != null) {
      sb.append("\n").append(thinkingBlock);
    }
    return sb.toString();
  }

  static String renderColumns(String left, int leftWidth, String right, int rightWidth, int gap) {
    var leftLines = left == null || left.isEmpty() ? List.<String>of() : List.of(left.split("\n", -1));
    var rightLines = right == null || right.isEmpty() ? List.<String>of() : List.of(right.split("\n", -1));
    int maxLines = Math.max(leftLines.size(), rightLines.size());
    var out = new ArrayList<String>();
    for (int i = 0; i < maxLines; i++) {
      String leftLine = i < leftLines.size() ? leftLines.get(i) : "";
      String rightLine = i < rightLines.size() ? rightLines.get(i) : "";
      out.add(padDisplayLine(leftLine, leftWidth) + " ".repeat(Math.max(1, gap)) + padDisplayLine(rightLine, rightWidth));
    }
    return String.join("\n", out);
  }

  private static String padDisplayLine(String line, int width) {
    String safe = line == null ? "" : line;
    int safeWidth = Math.max(1, width - 1);
    int current = Ansi.stringDisplayWidth(safe);
    if (current > safeWidth) {
      return Ansi.truncatePlain(Ansi.stripAnsi(safe), safeWidth);
    }
    return safe + " ".repeat(Math.max(0, safeWidth - current));
  }

  private String joinBadges(List<String> badges, int maxWidth) {
    var visibleBadges = badges.stream()
        .filter(b -> b != null && !b.isBlank())
        .toList();
    if (visibleBadges.isEmpty()) return "";
    String sep = Ansi.DARK_GRAY + " │ " + Ansi.RESET;
    String plain = String.join(sep, visibleBadges);
    if (Ansi.stringDisplayWidth(Ansi.stripAnsi(plain)) <= maxWidth) return plain;

    var result = new StringBuilder();
    for (String badge : visibleBadges) {
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

  private String joinBadgeTokens(List<String> badges, int maxWidth) {
    var visibleBadges = badges.stream()
        .filter(b -> b != null && !b.isBlank())
        .toList();
    if (visibleBadges.isEmpty()) return "";
    String sep = " ";
    String plain = String.join(sep, visibleBadges);
    if (Ansi.stringDisplayWidth(Ansi.stripAnsi(plain)) <= maxWidth) return plain;

    var result = new StringBuilder();
    for (String badge : visibleBadges) {
      String candidate = result.isEmpty() ? badge : result + sep + badge;
      if (Ansi.stringDisplayWidth(Ansi.stripAnsi(candidate)) > maxWidth) break;
      if (result.isEmpty()) result.append(badge);
      else result.append(sep).append(badge);
    }
    if (!result.isEmpty() && result.length() < plain.length()) {
      result.append("  ").append(Ansi.DIM).append("...").append(Ansi.RESET);
    }
    return result.toString();
  }

  // --- Line wrapping ---

  static List<String> wrapDisplayLines(List<String> inputLines, int width) {
    var wrapped = new ArrayList<String>();
    for (String line : inputLines) {
      wrapped.addAll(wrapDisplayLine(line, width));
    }
    return wrapped;
  }

  private static List<String> wrapDisplayLine(String line, int width) {
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
    var activeStyle = new StringBuilder();
    int currentWidth = 0;
    for (DisplayToken token : tokenizeDisplayLine(line)) {
      if (token.isAnsi()) {
        current.append(token.text());
        updateActiveStyle(activeStyle, token.text());
        continue;
      }
      int tokenWidth = token.width();
      if (currentWidth + tokenWidth > width && currentWidth > 0) {
        parts.add(finishWrappedLine(current, activeStyle));
        current = new StringBuilder();
        if (!activeStyle.isEmpty()) {
          current.append(activeStyle);
        }
        currentWidth = 0;
      }
      current.append(token.text());
      currentWidth += tokenWidth;
    }
    if (!current.isEmpty()) {
      parts.add(finishWrappedLine(current, activeStyle));
    }
    return parts;
  }

  private static List<DisplayToken> tokenizeDisplayLine(String line) {
    var tokens = new ArrayList<DisplayToken>();
    var plain = new StringBuilder();
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '\033' && i + 1 < line.length() && line.charAt(i + 1) == '[') {
        if (!plain.isEmpty()) {
          appendPlainTokens(tokens, plain.toString());
          plain.setLength(0);
        }
        int end = i + 2;
        while (end < line.length() && !Character.isLetter(line.charAt(end))) {
          end++;
        }
        if (end < line.length()) {
          tokens.add(new DisplayToken(line.substring(i, end + 1), 0, true));
          i = end;
          continue;
        }
      }
      plain.append(ch);
    }
    if (!plain.isEmpty()) {
      appendPlainTokens(tokens, plain.toString());
    }
    return tokens;
  }

  private static void appendPlainTokens(List<DisplayToken> tokens, String plain) {
    BreakIterator iterator = BreakIterator.getCharacterInstance();
    iterator.setText(plain);
    for (int start = iterator.first(), end = iterator.next();
         end != BreakIterator.DONE;
         start = end, end = iterator.next()) {
      String cluster = plain.substring(start, end);
      tokens.add(new DisplayToken(cluster, clusterDisplayWidth(cluster), false));
    }
  }

  private static void updateActiveStyle(StringBuilder activeStyle, String ansi) {
    if (!ansi.endsWith("m")) {
      return;
    }
    if (Ansi.RESET.equals(ansi) || "\033[0m".equals(ansi)) {
      activeStyle.setLength(0);
      return;
    }
    activeStyle.append(ansi);
  }

  private static String finishWrappedLine(StringBuilder current, StringBuilder activeStyle) {
    if (current.isEmpty()) {
      return "";
    }
    if (activeStyle.isEmpty()) {
      return current.toString();
    }
    return current + Ansi.RESET;
  }

  private static int clusterDisplayWidth(String cluster) {
    if (cluster == null || cluster.isEmpty()) {
      return 0;
    }
    boolean emojiLike = false;
    int width = 0;
    for (int cp : cluster.codePoints().toArray()) {
      if (isEmojiLikeCodePoint(cp)) {
        emojiLike = true;
      }
      int cw = Ansi.charDisplayWidth(cp);
      if (cw > 0) {
        width += cw;
      }
    }
    if (emojiLike) {
      return Math.max(2, width == 0 ? 2 : Math.min(width, 2));
    }
    return width;
  }

  private static boolean isEmojiLikeCodePoint(int codePoint) {
    return (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF)
        || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
        || codePoint == 0x20E3;
  }

  // --- Terminal helpers ---

  private int termWidth() {
    return Math.max(20, terminal.getSize().getColumns());
  }

  private record DisplayToken(String text, int width, boolean isAnsi) {
  }
}
