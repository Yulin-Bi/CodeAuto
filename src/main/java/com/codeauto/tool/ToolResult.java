package com.codeauto.tool;

public record ToolResult(boolean ok, String output, boolean awaitUser) {
  public static ToolResult ok(String output) {
    return new ToolResult(true, output, false);
  }

  public static ToolResult error(String output) {
    return new ToolResult(false, output, false);
  }

  public static ToolResult awaitUser(String output) {
    return new ToolResult(true, output, true);
  }
}
