package com.codeauto.tui;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiRendererTranscriptTest {
  @Test
  void userTranscriptBlockUsesPromptStyle() {
    String plain = Ansi.stripAnsi(TuiRenderer.renderUserTranscriptBlock(
        "first line\nsecond line"));

    assertEquals(">  first line \n>  second line ", plain);
  }

  @Test
  void assistantTranscriptBlockUsesSymbolMarker() {
    String plain = Ansi.stripAnsi(TuiRenderer.renderAssistantTranscriptBlock(
        "first line\nsecond line", false));

    assertEquals("> first line\n| second line", plain);
  }

  @Test
  void assistantErrorTranscriptBlockUsesErrorMarker() {
    String plain = Ansi.stripAnsi(TuiRenderer.renderAssistantTranscriptBlock(
        "failed", true));

    assertEquals("! failed", plain);
  }

  @Test
  void activityBlockUsesCompactEventLayout() {
    String plain = Ansi.stripAnsi(TuiRenderer.renderActivityBlock(
        "tool grep ok", "line one\nline two"));

    assertEquals("tool grep ok\n> line one\n> line two", plain);
  }

  @Test
  void activityBlockCanRenderGroupedSummary() {
    String plain = Ansi.stripAnsi(TuiRenderer.renderActivityBlock(
        "activity 3 events  tools 2", "tool grep ok\ntool read ok\nprogress [+]"));

    assertEquals("activity 3 events  tools 2\n> tool grep ok\n> tool read ok\n> progress [+]", plain);
  }

  @Test
  void wrappedUserTranscriptKeepsGrayBackgroundOnEveryWrappedLine() {
    TuiRenderer renderer = new TuiRenderer(null, null, 0);
    String block = TuiRenderer.renderUserTranscriptBlock("this is a long user line that should wrap and keep background");

    List<String> lines = renderer.wrapDisplayLines(List.of(block), 18);

    assertTrue(lines.size() > 1, lines.toString());
    for (String line : lines) {
      assertTrue(line.contains(Ansi.USER_BG), line);
      assertTrue(line.endsWith(Ansi.RESET), line);
    }
  }

  @Test
  void progressDetailsRenderOnlyWhenExplicitlyRequested() {
    String body = TuiRenderer.PROGRESS_RUNNING + "Running edit_file\n"
        + TuiRenderer.PROGRESS_SUCCESS + "Processed edit_file\n"
        + TuiRenderer.PROGRESS_INFO + "Refreshing prompt state";

    String plain = Ansi.stripAnsi(TuiRenderer.renderProgressDetails(body, 40));

    assertTrue(plain.contains("progress"));
    assertTrue(plain.contains("Running edit_file"));
    assertTrue(plain.contains("Processed edit_file"));
    assertTrue(plain.contains("Refreshing prompt state"));
  }
}
