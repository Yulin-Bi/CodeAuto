package com.codeauto.tui;

import com.codeauto.todo.TodoEntry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiRendererTodoTest {
  @Test
  void todoSnapshotShowsGroupedPlanWithActiveAndCompletedItems() {
    Instant now = Instant.parse("2026-06-17T00:00:00Z");
    List<TodoEntry> todos = List.of(
        new TodoEntry("a", "Write regression test", "in_progress", "Writing regression test",
            "todo-panel", "Todo panel polish", now, now),
        new TodoEntry("b", "Tighten footer layout", "pending", "Tightening footer layout",
            "todo-panel", "Todo panel polish", now, now),
        new TodoEntry("c", "Done item", "completed", "Done item",
            "todo-panel", "Todo panel polish", now, now));

    String plain = Ansi.stripAnsi(TuiRenderer.renderTodoSnapshot(todos));

    assertEquals(
        "Todo panel polish\n"
            + "▣ Writing regression test\n"
            + "☐ Tighten footer layout\n"
            + "☑ Done item",
        plain);
  }

  @Test
  void todoSnapshotLimitsVisibleItemsPerGroup() {
    Instant now = Instant.parse("2026-06-17T00:00:00Z");
    List<TodoEntry> todos = List.of(
        new TodoEntry("a", "Active item", "in_progress", "Active item",
            "todo-group", "Todo group", now, now),
        new TodoEntry("b", "Pending one", "pending", "Pending one",
            "todo-group", "Todo group", now, now),
        new TodoEntry("c", "Pending two", "pending", "Pending two",
            "todo-group", "Todo group", now, now),
        new TodoEntry("d", "Pending three", "pending", "Pending three",
            "todo-group", "Todo group", now, now),
        new TodoEntry("e", "Done one", "completed", "Done one",
            "todo-group", "Todo group", now, now),
        new TodoEntry("f", "Done two", "completed", "Done two",
            "todo-group", "Todo group", now.plusSeconds(1), now.plusSeconds(1)),
        new TodoEntry("g", "Done three", "completed", "Done three",
            "todo-group", "Todo group", now.plusSeconds(2), now.plusSeconds(2)));

    String plain = Ansi.stripAnsi(TuiRenderer.renderTodoSnapshot(todos));

    assertTrue(plain.contains("还有 1 项未完成"));
    assertTrue(plain.contains("还有 1 项已完成"));
    assertTrue(!plain.contains("Pending three"));
    assertTrue(!plain.contains("Done three"));
  }

  @Test
  void todoPanelBodyWrapsLongTaskTextInsteadOfTruncating() {
    Instant now = Instant.parse("2026-06-17T00:00:00Z");
    String longTask = "Investigate why the todo sidebar truncates long task descriptions in the TUI panel";
    List<TodoEntry> todos = List.of(
        new TodoEntry("a", longTask, "pending", longTask,
            "todo-wrap", "Wrap long todo text", now, now));

    String plain = Ansi.stripAnsi(TuiRenderer.renderTodoPanelBody(todos, 24));
    String normalized = plain.replace("\n", "");

    assertTrue(plain.contains("Wrap long todo text"));
    assertTrue(normalized.contains(longTask));
    assertTrue(!plain.contains("..."));
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
