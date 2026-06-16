package com.codeauto.tool;

import com.codeauto.permissions.PermissionManager;
import java.nio.file.Path;

public record ToolContext(Path cwd, PermissionManager permissions, String toolCallId) {

  public ToolContext(Path cwd, PermissionManager permissions) {
    this(cwd, permissions, null);
  }

  public ToolContext withToolCallId(String toolCallId) {
    return new ToolContext(this.cwd, this.permissions, toolCallId);
  }
}
