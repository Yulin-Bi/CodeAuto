package com.codeauto.undo;

import java.time.Instant;

public record UndoRecord(
    String id,
    String toolCallId,
    String toolName,
    String filePath,
    String beforeContent,
    Instant timestamp,
    boolean undone
) {

  public UndoRecord markUndone() {
    return new UndoRecord(id, toolCallId, toolName, filePath, beforeContent, timestamp, true);
  }
}
