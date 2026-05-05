package com.codeauto.tui;

import com.codeauto.context.ContextStats;
import com.codeauto.todo.TodoStore;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import org.jline.terminal.Terminal;

/**
 * Terminal rendering for the TUI. Holds {@link Terminal} and {@link Writer}
 * references; reads state from a {@link TuiApp} parameter at render time.
 */
final class TuiRenderer {

  private static final int CONTEXT_WINDOW = 200_000;
  private static final int SCROLL_STEP = 5;
  private static final int SLASH_MENU_MAX_ROWS = 7;
  private static final int LIVE_PROGRESS_MAX_LINES = 5;

  private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};
  static final String PROGRESS_RUNNING = "RUNNING::";
  static final String PROGRESS_SUCCESS = "SUCCESS::";
  static final String PROGRESS_ERROR = "ERROR::";
  static final String PROGRESS_INFO = "INFO::";

  private final Terminal terminal;
  private final Writer writer;

  TuiRenderer(Terminal terminal, Writer writer) {
    this.terminal = terminal;
    this.writer = writer;
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

    String headerPanel = buildHeaderBody(width, app);

    String bottomPanel;
    if (app.sessionPicker() != null) {
      bottomPanel = renderSessionPickerPanel(width, app.sessionPicker());
    } else if (app.pendingApproval() != null) {
      bottomPanel = renderApprovalPanel(width, app);
    } else {
      bottomPanel = renderPromptPanel(width, app);
    }

    int fixedLines = lineCount(headerPanel) + 1
        + lineCount(bottomPanel)
        + 1
        + 1;
    int transcriptPanelOverhead = 5;
    int transcriptMaxLines = Math.max(3, height - fixedLines - transcriptPanelOverhead);

    String transcriptBody = buildTranscriptBody(width, transcriptMaxLines, app);
    String rightTitle = app.transcriptSize() + " EVENTS";
    ContextStats stats = app.contextStats();
    if (stats != null) {
      rightTitle += " - CTX=" + stats.estimatedTokens() + " - " + colorStatus(stats.warningLevel());
    }
    String transcriptPanel = PanelRenderer.renderFeedPanel("session feed", transcriptBody, width, rightTitle);

    sb.append(headerPanel).append("\n");
    sb.append(transcriptPanel).append("\n\n");

    sb.append(bottomPanel);

    sb.append("\n").append(renderFooterBar(width, app));

    sb.append("\033[J");

    if (app.sessionPicker() == null && app.pendingApproval() == null) {
      int promptPanelStartRow = lineCount(headerPanel) + 1
          + lineCount(transcriptPanel) + 2;
      String input = app.inputText();
      int cursorPos = app.cursorPos();
      int safeCursor = Math.max(0, Math.min(cursorPos, input == null ? 0 : input.length()));
      int inputOffset = Ansi.stringDisplayWidth("CodeAuto> ")
          + Ansi.stringDisplayWidth((input == null ? "" : input).substring(0, safeCursor));
      int inputWidth = Math.max(1, width);
      int cursorRow = promptPanelStartRow + 1 + (inputOffset / inputWidth);
      int cursorCol = 1 + (inputOffset % inputWidth);
      sb.append("\033[").append(Math.max(1, cursorRow)).append(";")
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
    badges.add(metric("session", app.sessionId(), Ansi.BRIGHT_YELLOW));
    badges.add(metric("model", modelName, Ansi.GREEN));
    badges.add(metric("messages", String.valueOf(app.messageCount()), Ansi.BRIGHT_CYAN));
    badges.add(metric("tools", String.valueOf(app.toolCount()), Ansi.MAGENTA));
    if (app.contextStats() != null) {
      badges.add(renderContextBadge(app.contextStats()));
    }
    badges.add(metric("skills", app.skillCount() >= 0 ? String.valueOf(app.skillCount()) : "?", Ansi.BRIGHT_CYAN));
    badges.add(renderTodoBadge(app));

    var badgeLine = joinBadges(badges, termWidth);
    sb.append(badgeLine);
    sb.append("\n");
    sb.append(Ansi.DARK_GRAY).append("─".repeat(Math.max(0, termWidth))).append(Ansi.RESET);

    return sb.toString();
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
    String separator = Ansi.BLUE + Ansi.DIM + "·" + Ansi.RESET;

    List<TranscriptEntry> snapshot = app.transcriptSnapshot();

    for (int idx = 0; idx < snapshot.size(); idx++) {
      var entry = snapshot.get(idx);
      if (app.hasPinnedProgress()
          && app.progressTraceEntryId() != null
          && entry instanceof TranscriptEntry.Progress p
          && p.id() == app.progressTraceEntryId()) {
        continue;
      }
      if (!lines.isEmpty()) {
        lines.add(separator);
      }
      lines.addAll(List.of(renderTranscriptEntry(entry, app).split("\n")));
    }

    if (!app.hasPinnedProgress()) {
      app.setCachedRenderLines(lines);
      app.setTranscriptDirty(false);
    }
    return lines;
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

  // --- Footer ---

  private String renderFooterBar(int termWidth, TuiApp app) {
    var left = new StringBuilder();
    String statusText = app.statusText();
    int spinnerFrame = app.spinnerFrame();
    if (statusText != null) {
      left.append(Ansi.YELLOW)
          .append(Ansi.BOLD)
          .append(SPINNER_FRAMES[spinnerFrame])
          .append(" ")
          .append(app.statusWithElapsed())
          .append(Ansi.RESET);
    } else {
      left.append(Ansi.DIM).append("Ready").append(Ansi.RESET);
    }

    var right = new StringBuilder();
    var bgTasks = com.codeauto.background.BackgroundTaskRegistry.get().list();
    long runningCount = bgTasks.stream().filter(t -> "running".equals(t.status())).count();
    if (runningCount > 0) {
      right.append("  ").append(Ansi.DIM).append("shells").append(Ansi.RESET)
          .append(" ").append(Ansi.BRIGHT_CYAN).append(runningCount).append(Ansi.RESET);
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

  // --- Entry rendering ---

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
      return "· " + raw.substring(PROGRESS_INFO.length());
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

  static int lineCount(String text) {
    if (text == null || text.isEmpty()) return 0;
    return text.split("\n", -1).length;
  }

  static String metric(String label, String value, String color) {
    return color + label + Ansi.RESET + " " + Ansi.BOLD + value + Ansi.RESET;
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
        (int) ((double) stats.estimatedTokens() / CONTEXT_WINDOW * 100)));
    String color = switch (stats.warningLevel()) {
      case "warning" -> Ansi.YELLOW;
      case "critical" -> Ansi.RED;
      case "blocked" -> Ansi.BRIGHT_RED;
      default -> Ansi.GREEN;
    };
    return color + "ctx" + Ansi.RESET + " " + Ansi.BOLD + pct + "%" + Ansi.RESET;
  }

  private String renderTodoBadge(TuiApp app) {
    try {
      var todos = new TodoStore(app.cwd()).list(null);
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

  // --- Line wrapping ---

  List<String> wrapDisplayLines(List<String> inputLines, int width) {
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

  // --- Terminal helpers ---

  private int termWidth() {
    return Math.max(20, terminal.getSize().getColumns());
  }
}
