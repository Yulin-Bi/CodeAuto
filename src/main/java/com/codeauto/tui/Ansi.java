package com.codeauto.tui;

public class Ansi {
  private Ansi() {}

  public static final String RESET = "[0m";
  public static final String DIM = "[2m";
  public static final String CYAN = "[36m";
  public static final String GREEN = "[32m";
  public static final String YELLOW = "[33m";
  public static final String RED = "[31m";
  public static final String BLUE = "[34m";
  public static final String MAGENTA = "[35m";
  public static final String BOLD = "[1m";
  public static final String REVERSE = "[7m";
  public static final String BRIGHT_GREEN = "[92m";
  public static final String BRIGHT_RED = "[91m";
  public static final String BRIGHT_CYAN = "[96m";
  public static final String BRIGHT_YELLOW = "[93m";
  public static final String BORDER = "[38;5;31m";

  public static final String ENTER_ALT = "[?1049h";
  public static final String EXIT_ALT = "[?1049l";
  public static final String CLEAR = "[2J[H";
  public static final String HIDE_CURSOR = "[?25l";
  public static final String SHOW_CURSOR = "[?25h";
  public static final String ENABLE_SGR_MOUSE = "[?1000h[?1002h[?1006h";
  public static final String DISABLE_SGR_MOUSE = "[?1000l[?1002l[?1006l";

  public static String stripAnsi(String input) {
    return input.replaceAll("\\[[0-9;]*[a-zA-Z]", "");
  }

  public static int charDisplayWidth(int codePoint) {
    if (codePoint >= 0x1100 && (codePoint <= 0x115f || codePoint == 0x2329
        || codePoint == 0x232a
        || (codePoint >= 0x2e80 && codePoint <= 0xa4cf && codePoint != 0x303f)
        || (codePoint >= 0xac00 && codePoint <= 0xd7a3)
        || (codePoint >= 0xf900 && codePoint <= 0xfaff)
        || (codePoint >= 0xfe10 && codePoint <= 0xfe19)
        || (codePoint >= 0xfe30 && codePoint <= 0xfe6f)
        || (codePoint >= 0xff00 && codePoint <= 0xff60)
        || (codePoint >= 0xffe0 && codePoint <= 0xffe6)
        || (codePoint >= 0x1f300 && codePoint <= 0x1faf6)
        || (codePoint >= 0x20000 && codePoint <= 0x3fffd))) {
      return 2;
    }
    return 1;
  }

  public static int stringDisplayWidth(String input) {
    String plain = stripAnsi(input);
    return plain.codePoints().map(Ansi::charDisplayWidth).sum();
  }

  public static String truncatePlain(String input, int width) {
    if (width <= 0) return "";
    if (stringDisplayWidth(input) <= width) return input;
    if (width <= 3) return input.substring(0, Math.min(input.length(), width));
    int target = width - 3;
    var sb = new StringBuilder();
    int used = 0;
    for (int cp : input.codePoints().toArray()) {
      int cw = charDisplayWidth(cp);
      if (used + cw > target) break;
      sb.appendCodePoint(cp);
      used += cw;
    }
    return sb + "...";
  }

  public static String padPlain(String input, int width) {
    int visible = stringDisplayWidth(input);
    if (visible >= width) return input;
    return input + " ".repeat(width - visible);
  }

  public static String truncatePathMiddle(String input, int width) {
    if (width <= 0 || stringDisplayWidth(input) <= width) return input;
    if (width <= 5) return truncatePlain(input, width);
    int keep = width - 3;
    int leftTarget = (keep + 1) / 2;
    int rightTarget = keep / 2;

    int[] codePoints = input.codePoints().toArray();
    var left = new StringBuilder();
    int leftWidth = 0;
    for (int cp : codePoints) {
      int cw = charDisplayWidth(cp);
      if (leftWidth + cw > leftTarget) break;
      left.appendCodePoint(cp);
      leftWidth += cw;
    }

    var right = new StringBuilder();
    int rightWidth = 0;
    for (int i = codePoints.length - 1; i >= 0; i--) {
      int cp = codePoints[i];
      int cw = charDisplayWidth(cp);
      if (rightWidth + cw > rightTarget) break;
      right.insert(0, (char) cp);
      rightWidth += cw;
    }

    return left + "..." + right;
  }
}
