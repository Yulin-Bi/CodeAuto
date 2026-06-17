package com.codeauto.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TuiRendererBadgeTest {
  @Test
  void metricUsesUnifiedTokenShape() {
    String plain = Ansi.stripAnsi(TuiRenderer.metric("session", "main"));
    assertEquals("[session: main]", plain);
  }

  @Test
  void metricNormalizesBlankValues() {
    String plain = Ansi.stripAnsi(TuiRenderer.metric("model", ""));
    assertEquals("[model: -]", plain);
  }
}
