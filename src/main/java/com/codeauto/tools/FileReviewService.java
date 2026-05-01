package com.codeauto.tools;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class FileReviewService {
  private FileReviewService() {
  }

  static ToolResult reviewAndWrite(Path file, String before, String after, ToolContext context, String verb) throws Exception {
    if (before.equals(after)) {
      return ToolResult.ok("No changes for " + file);
    }
    if (!context.permissions().canWrite(file)) {
      return ToolResult.error("Write path is not allowed: " + file + context.permissions().formatLastDenialFeedback());
    }
    String diff = unifiedDiff(file, before, after);
    Files.createDirectories(file.getParent());
    Files.writeString(file, after);
    return ToolResult.ok(verb + " " + file + "\n" + diff);
  }

  static String unifiedDiff(Path file, String before, String after) {
    List<String> beforeLines = before.isEmpty() ? List.of() : before.lines().toList();
    List<String> afterLines = after.isEmpty() ? List.of() : after.lines().toList();
    Patch<String> patch = DiffUtils.diff(beforeLines, afterLines);
    List<String> lines = UnifiedDiffUtils.generateUnifiedDiff(
        file.toString(), file.toString(), beforeLines, patch, 3);
    return String.join("\n", lines);
  }
}
