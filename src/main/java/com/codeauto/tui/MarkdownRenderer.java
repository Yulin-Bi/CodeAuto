package com.codeauto.tui;

public class MarkdownRenderer {
  private MarkdownRenderer() {}

  public static String render(String input) {
    if (input == null || input.isBlank()) return input == null ? "" : input;
    var sb = new StringBuilder();
    boolean inCodeBlock = false;
    for (String line : input.split("\n")) {
      if (sb.length() > 0) sb.append("\n");
      String formatted = line;

      if (line.startsWith("```")) {
        inCodeBlock = !inCodeBlock;
        sb.append(Ansi.DIM).append(line).append(Ansi.RESET);
        continue;
      }

      if (inCodeBlock) {
        sb.append(Ansi.DIM).append(line).append(Ansi.RESET);
        continue;
      }

      if (line.trim().matches("^\\|\\s*:?-+:?\\s*\\|.*$")) {
        sb.append(Ansi.DIM).append(line.replace("|", " ").trim()).append(Ansi.RESET);
        continue;
      }

      if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
        String trimmed = line.trim();
        String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|");
        var cellParts = new java.util.ArrayList<String>();
        for (String cell : cells) {
          cellParts.add(cell.trim());
        }
        sb.append(String.join(" " + Ansi.DIM + "|" + Ansi.RESET + " ", cellParts));
        continue;
      }

      if (line.startsWith("### ")) {
        sb.append(Ansi.CYAN).append(Ansi.BOLD).append(line.substring(4)).append(Ansi.RESET);
        continue;
      }

      if (line.startsWith("## ")) {
        sb.append(Ansi.CYAN).append(Ansi.BOLD).append(line.substring(3)).append(Ansi.RESET);
        continue;
      }

      if (line.startsWith("# ")) {
        sb.append(Ansi.CYAN).append(Ansi.BOLD).append(line.substring(2)).append(Ansi.RESET);
        continue;
      }

      if (line.startsWith("> ")) {
        sb.append(Ansi.DIM).append(line).append(Ansi.RESET);
        continue;
      }

      if (line.matches("^\\s*[-*]\\s+.*")) {
        formatted = line.replaceAll("^\\s*[-*]\\s+", Ansi.YELLOW + "•" + Ansi.RESET + " ");
      }

      formatted = formatted.replaceAll("`([^`]+)`", Ansi.MAGENTA + "$1" + Ansi.RESET);
      formatted = formatted.replaceAll("\\*\\*([^*]+)\\*\\*", Ansi.BOLD + "$1" + Ansi.RESET);

      sb.append(formatted);
    }
    return sb.toString();
  }
}
