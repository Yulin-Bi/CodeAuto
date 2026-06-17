package com.codeauto.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
