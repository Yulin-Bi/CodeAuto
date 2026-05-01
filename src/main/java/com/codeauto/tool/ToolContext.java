package com.codeauto.tool;

import com.codeauto.permissions.PermissionManager;
import java.nio.file.Path;

public record ToolContext(Path cwd, PermissionManager permissions) {
}
