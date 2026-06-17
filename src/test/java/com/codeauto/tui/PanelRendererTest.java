package com.codeauto.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelRendererTest {
  @Test
  void feedPanelUsesOpenLayoutWithoutSideBorders() {
    String rendered = PanelRenderer.renderFeedPanel("Session Feed", "A line\nB line", 32, "4 events");
    String plain = Ansi.stripAnsi(rendered);

    assertTrue(plain.startsWith("Session Feed  4 events"));
    assertFalse(plain.contains("--------------------------------"));
    assertFalse(plain.contains("| A line |"));
    assertFalse(plain.contains("+----------------"));
  }
}
