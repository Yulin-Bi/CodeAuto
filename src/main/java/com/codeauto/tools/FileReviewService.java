package com.codeauto.tools;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolResult;
import com.codeauto.undo.UndoStore;
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

    // Layer 1 undo recording: save pre-write state AFTER successful write, so a write failure
    // does not leave a stale undo record for an operation that never actually happened.
    String toolCallId = context.toolCallId();
    if (toolCallId != null) {
      try {
        new UndoStore(context.cwd()).save(toolCallId, verb, file, before);
      } catch (Exception e) {
        // Best-effort: undo storage failure must not block the file write.
        System.err.println("[CodeAuto] Failed to save undo record: " + e.getMessage());
      }
    }

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
