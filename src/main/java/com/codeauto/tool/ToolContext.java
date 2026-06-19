package com.codeauto.tool;

import com.codeauto.permissions.PermissionManager;
import java.nio.file.Path;

public record ToolContext(Path cwd, PermissionManager permissions, String toolCallId, String turnId) {

  public ToolContext(Path cwd, PermissionManager permissions) {
    this(cwd, permissions, null, null);
  }

  public ToolContext(Path cwd, PermissionManager permissions, String toolCallId) {
    this(cwd, permissions, toolCallId, null);
  }

  public ToolContext withToolCallId(String toolCallId) {
    return new ToolContext(this.cwd, this.permissions, toolCallId, this.turnId);
  }

  public ToolContext withTurnId(String turnId) {
    return new ToolContext(this.cwd, this.permissions, this.toolCallId, turnId);
  }
}
