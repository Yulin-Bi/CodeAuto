package com.codeauto.tui;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.jline.terminal.Terminal;

/** Terminal escape sequence and mouse event parsing. */
final class TuiInputParser {

  private TuiInputParser() {}

  static boolean isCompleteEscapeSequence(CharSequence seq) {
    int len = seq.length();
    if (len <= 1) return false;
    if (seq.charAt(1) == 'O') return len >= 3;
    if (seq.charAt(1) != '[') return true;
    if (len < 3) return false;
    char last = seq.charAt(len - 1);
    if (seq.charAt(2) == '<') {
      return last == 'M' || last == 'm';
    }
    if (Character.isDigit(seq.charAt(2))) {
      return last == 'M'
          || last == 'm'
          || last == '~'
          || (last >= '@' && last <= '~' && !Character.isDigit(last) && last != ';');
    }
    if (len == 3 && seq.charAt(2) == 'M') return false;
    return (last >= '@' && last <= '~');
  }

  static String readEscapeSequence(int first, Terminal terminal) throws IOException {
    var seq = new StringBuilder();
    seq.append((char) first);

    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40);
    while (System.nanoTime() < deadline) {
      if (!terminal.reader().ready()) {
        try {
          Thread.sleep(2);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        continue;
      }

      int next = terminal.reader().read();
      if (next < 0) break;
      seq.append((char) next);
      if (isCompleteEscapeSequence(seq)) break;
      if (seq.length() >= 6 && seq.charAt(1) == '[' && seq.charAt(2) == 'M') break;
    }
    return seq.toString();
  }

  /**
   * Parse SGR or X10 mouse scroll event.
   * Returns scroll delta (positive = down, negative = up), or null if not a scroll event.
   */
  static Integer parseMouseScroll(String seq) {
    if (seq.length() == 6 && seq.charAt(1) == '[' && seq.charAt(2) == 'M') {
      return parseX10Mouse(seq);
    }
    if (seq.length() > 3 && seq.charAt(2) == '<') {
      return parseSgrMouse(seq);
    }
    if (seq.length() > 3 && Character.isDigit(seq.charAt(2))) {
      return parseUrxvtMouse(seq);
    }
    return null;
  }

  private static Integer parseSgrMouse(String seq) {
    try {
      String inner = seq.substring(3, seq.length() - 1);
      String[] parts = inner.split(";");
      if (parts.length != 3) return null;
      int btn = Integer.parseInt(parts[0]);
      if ((btn & 0x40) != 0) {
        return (btn & 0x01) == 0 ? -3 : 3;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private static Integer parseX10Mouse(String seq) {
    try {
      int btn = seq.charAt(3) - 0x20;
      if ((btn & 0x40) != 0) {
        return (btn & 0x01) == 0 ? -3 : 3;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private static Integer parseUrxvtMouse(String seq) {
    try {
      String inner = seq.substring(2, seq.length() - 1);
      String[] parts = inner.split(";");
      if (parts.length != 3) return null;
      int btn = Integer.parseInt(parts[0]);
      if ((btn & 0x40) != 0) {
        return (btn & 0x01) == 0 ? -3 : 3;
      }
    } catch (Exception ignored) {
    }
    return null;
  }
}
