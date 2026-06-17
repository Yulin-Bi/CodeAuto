package com.codeauto.tui;

import java.util.ArrayList;
import java.util.List;

public class PanelRenderer {
  private PanelRenderer() {}

  public static String renderPanel(String title, String body, int termWidth, String rightTitle) {
    int width = Math.max(20, termWidth);
    int inner = width - 4;
    List<String> bodyLines = new ArrayList<>();
    if (body != null && !body.isEmpty()) {
      for (String line : body.split("\n")) {
        bodyLines.addAll(wrapPanelBodyLine(line, width));
      }
    }

    var sb = new StringBuilder();
    sb.append(borderLine("top", width)).append("\n");
    String rt = rightTitle != null && !rightTitle.isBlank()
        ? Ansi.DIM + Ansi.truncatePlain(rightTitle, Math.max(10, inner / 3)) + Ansi.RESET
        : null;
    sb.append(panelRow(Ansi.BRIGHT_CYAN + Ansi.BOLD + title + Ansi.RESET, width, rt)).append("\n");
    for (String line : bodyLines) {
      sb.append(panelRow(line, width, null)).append("\n");
    }
    sb.append(borderLine("bottom", width));
    return sb.toString();
  }

  public static String renderPanel(String title, String body, int termWidth) {
    return renderPanel(title, body, termWidth, null);
  }

  public static String renderFeedPanel(String title, String body, int termWidth, String rightTitle) {
    int width = Math.max(20, termWidth);
    List<String> bodyLines = body == null || body.isEmpty()
        ? List.of()
        : List.of(body.split("\n", -1));

    String meta = rightTitle == null || rightTitle.isBlank()
        ? ""
        : Ansi.DIM + "  " + Ansi.truncatePlain(rightTitle, Math.max(10, width / 2)) + Ansi.RESET;
    var sb = new StringBuilder();
    sb.append(Ansi.LIGHT_BLUE).append(Ansi.BOLD).append(title).append(Ansi.RESET).append(meta);
    if (!bodyLines.isEmpty()) {
      sb.append("\n");
    }
    for (int i = 0; i < bodyLines.size(); i++) {
      sb.append(padOrTrim(bodyLines.get(i), width));
      if (i < bodyLines.size() - 1) {
        sb.append("\n");
      }
    }
    return sb.toString();
  }

  public static String renderLightPanel(String title, String body, int termWidth) {
    int width = Math.max(20, termWidth);
    int inner = Math.max(0, width - 4);
    List<String> bodyLines = new ArrayList<>();
    if (body != null && !body.isEmpty()) {
      for (String line : body.split("\n")) {
        bodyLines.addAll(wrapPanelBodyLine(line, width));
      }
    }
    var sb = new StringBuilder();
    sb.append(Ansi.GRAY).append("+").append(Ansi.BOLD).append(title.toUpperCase()).append(Ansi.RESET).append("\n");
    sb.append(Ansi.GRAY).append("+").append("-".repeat(Math.max(0, width - 2))).append("+").append(Ansi.RESET).append("\n");
    for (String line : bodyLines) {
      sb.append(Ansi.GRAY).append("|").append(Ansi.RESET)
          .append(" ")
          .append(padOrTrim(line, inner))
          .append(" ")
          .append(Ansi.GRAY).append("|").append(Ansi.RESET).append("\n");
    }
    sb.append(Ansi.GRAY).append("+").append("-".repeat(Math.max(0, width - 2))).append("+").append(Ansi.RESET);
    return sb.toString();
  }

  private static String borderLine(String kind, int width) {
    int inner = Math.max(0, width - 2);
    if (kind.equals("top")) {
      return Ansi.BORDER + "+" + "-".repeat(inner) + "+" + Ansi.RESET;
    }
    return Ansi.BORDER + "+" + "-".repeat(inner) + "+" + Ansi.RESET;
  }

  private static String panelRow(String left, int width, String right) {
    int inner = Math.max(0, width - 4);
    String rightText = right != null ? right : "";
    String safeLeft = left + Ansi.RESET;
    int leftWidth = Ansi.stringDisplayWidth(safeLeft);
    int rightWidth = Ansi.stringDisplayWidth(rightText);
    String leftAdjusted;
    if (leftWidth + rightWidth > inner) {
      leftAdjusted = Ansi.truncatePlain(safeLeft, Math.max(0, inner - rightWidth - 1)) + Ansi.RESET;
    } else {
      leftAdjusted = safeLeft;
    }

    int leftPrinted = Ansi.stringDisplayWidth(leftAdjusted);
    int pad = Math.max(0, inner - leftPrinted - rightWidth);
    return Ansi.BORDER + "|" + Ansi.RESET + " "
        + leftAdjusted + " ".repeat(pad) + rightText + " "
        + Ansi.BORDER + "|" + Ansi.RESET;
  }

  private static List<String> wrapPanelBodyLine(String line, int width) {
    int inner = Math.max(0, width - 4);
    var parts = new ArrayList<String>();
    if (inner <= 0) {
      parts.add("");
      return parts;
    }
    String plain = Ansi.stripAnsi(line);
    if (Ansi.stringDisplayWidth(plain) <= inner) {
      parts.add(line);
      return parts;
    }
    var current = new StringBuilder();
    int currentWidth = 0;
    for (int cp : plain.codePoints().toArray()) {
      int cw = Ansi.charDisplayWidth(cp);
      if (currentWidth + cw > inner) {
        parts.add(current.toString());
        current = new StringBuilder();
        current.appendCodePoint(cp);
        currentWidth = cw;
      } else {
        current.appendCodePoint(cp);
        currentWidth += cw;
      }
    }
    if (!current.isEmpty()) {
      parts.add(current.toString());
    }
    return parts;
  }

  private static String padOrTrim(String line, int width) {
    int safeWidth = Math.max(1, width - 1);
    int current = Ansi.stringDisplayWidth(line);
    if (current > safeWidth) return Ansi.truncatePlain(Ansi.stripAnsi(line), safeWidth);
    return line + " ".repeat(Math.max(0, safeWidth - current));
  }
}
