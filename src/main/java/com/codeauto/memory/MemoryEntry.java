package com.codeauto.memory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record MemoryEntry(
    String id,
    MemoryType type,
    String title,
    String project,
    List<String> tags,
    Instant createdAt,
    Instant updatedAt,
    String content,
    Path path
) {
  public boolean stale(Instant now) {
    return updatedAt != null && updatedAt.plusSeconds(24 * 60 * 60).isBefore(now);
  }
}
