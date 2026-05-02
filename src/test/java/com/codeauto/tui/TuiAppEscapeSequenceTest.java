package com.codeauto.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiAppEscapeSequenceTest {
  @Test
  void bracketPrefixIsNotACompleteEscapeSequence() {
    assertFalse(TuiApp.isCompleteEscapeSequence("\033["));
  }

  @Test
  void recognizesKeyboardAndMouseEscapeSequences() {
    assertTrue(TuiApp.isCompleteEscapeSequence("\033[A"));
    assertTrue(TuiApp.isCompleteEscapeSequence("\033[5~"));
    assertTrue(TuiApp.isCompleteEscapeSequence("\033[1;5B"));
    assertTrue(TuiApp.isCompleteEscapeSequence("\033[<64;10;5M"));
  }
}
