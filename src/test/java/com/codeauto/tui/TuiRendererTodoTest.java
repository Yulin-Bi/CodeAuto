package com.codeauto.tui;

import com.codeauto.todo.TodoEntry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiRendererTodoTest {
  @Test
  void todoSnapshotShowsNowAndNextItems() {
    Instant now = Instant.parse("2026-06-17T00:00:00Z");
    List<TodoEntry> todos = List.of(
        new TodoEntry("a", "Write regression test", "in_progress", "Writing regression test", now, now),
        new TodoEntry("b", "Tighten footer layout", "pending", "Tightening footer layout", now, now),
        new TodoEntry("c", "Trim feed separators", "pending", "Trimming feed separators", now, now),
        new TodoEntry("d", "Done item", "completed", "Done item", now, now));

    String plain = Ansi.stripAnsi(TuiRenderer.renderTodoSnapshot(todos));

    assertEquals(
        ">> 1  - 2  x 1\n"
            + ">> Writing regression test\n"
            + "- Tighten footer layout\n"
            + "- Trim feed separators",
        plain);
  }

  @Test
  void columnsCanPlaceTodoBesideTranscript() {
    String plain = Ansi.stripAnsi(TuiRenderer.renderColumns(
        "Session Feed\nreply", 14,
        "ToDo\n- task", 12,
        3));

    assertEquals(
        "Session Feed    ToDo       \n"
            + "reply           - task     ",
        plain);
  }

  @Test
  void transcriptBudgetKeepsFeedAreaUsable() {
    int lines = TuiRenderer.computeTranscriptMaxLines(30, 4, 4, 3, 0);

    assertEquals(14, lines);
    assertTrue(lines > 3);
  }

  @Test
  void cursorRowIsClampedWithinTerminalHeight() {
    assertEquals(30, TuiRenderer.clampCursorRow(30, 33));
    assertEquals(1, TuiRenderer.clampCursorRow(30, 0));
  }

  @Test
  void composedScreenContentCountsSeparatorsAndBlankLines() {
    String screen = TuiRenderer.composeScreenContent(
        20,
        "head1\nhead2",
        false,
        "ToDo\ntask",
        "Session Feed\nreply",
        "Compose\nprompt",
        "ready",
        null);

    assertEquals(13, TuiRenderer.lineCount(screen));
  }
}
