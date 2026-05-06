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
    Path path,
    String bulletId,
    int helpfulCount,
    int harmfulCount,
    String section
) {
  public MemoryEntry {
    if (bulletId == null) bulletId = "";
    if (section == null) section = "";
    if (project == null) project = "";
    if (tags == null) tags = List.of();
  }

  public boolean stale(Instant now) {
    return updatedAt != null && updatedAt.plusSeconds(24 * 60 * 60).isBefore(now);
  }

  public boolean isBullet() {
    return bulletId != null && !bulletId.isEmpty();
  }
}
