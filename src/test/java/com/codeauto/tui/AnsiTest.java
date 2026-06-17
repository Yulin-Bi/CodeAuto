package com.codeauto.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnsiTest {
  @Test
  void zeroWidthEmojiJoinersDoNotConsumeColumns() {
    assertEquals(0, Ansi.charDisplayWidth(0x200D));
    assertEquals(0, Ansi.charDisplayWidth(0xFE0F));
    assertEquals(0, Ansi.charDisplayWidth(0x1F3FB));
  }
}
