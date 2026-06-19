package com.codeauto.memory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

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
    String section,
    String tier,
    int supportCount,
    int retrieveCount,
    Instant lastRetrievedAt,
    Instant lastInjectedAt,
    Instant lastSupportedAt,
    Instant lastHelpfulAt,
    Instant lastHarmfulAt
) {
  public MemoryEntry {
    bulletId = normalizeBulletId(bulletId);
    if (section == null) section = "";
    if (project == null) project = "";
    if (tags == null) tags = List.of();
    if (isBulletId(bulletId)) {
      tier = normalizeTier(tier, helpfulCount, harmfulCount);
      if (supportCount < 0) supportCount = 0;
      if (lastRetrievedAt == null) lastRetrievedAt = Instant.EPOCH;
      if (lastInjectedAt == null) lastInjectedAt = Instant.EPOCH;
      if (lastSupportedAt == null) lastSupportedAt = Instant.EPOCH;
      if (lastHelpfulAt == null) lastHelpfulAt = Instant.EPOCH;
      if (lastHarmfulAt == null) lastHarmfulAt = Instant.EPOCH;
    } else {
      tier = "";
      supportCount = 0;
      retrieveCount = 0;
      lastRetrievedAt = Instant.EPOCH;
      lastInjectedAt = Instant.EPOCH;
      lastSupportedAt = Instant.EPOCH;
      lastHelpfulAt = Instant.EPOCH;
      lastHarmfulAt = Instant.EPOCH;
    }
  }

  public MemoryEntry(
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
    this(
        id,
        type,
        title,
        project,
        tags,
        createdAt,
        updatedAt,
        content,
        path,
        bulletId,
        helpfulCount,
        harmfulCount,
        section,
        "",
        0,
        0,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  public boolean stale(Instant now) {
    return updatedAt != null && updatedAt.plusSeconds(24 * 60 * 60).isBefore(now);
  }

  public boolean isBullet() {
    return bulletId != null && !bulletId.isEmpty();
  }

  public boolean isHot() {
    return "hot".equals(tier);
  }

  public boolean isWarm() {
    return "warm".equals(tier);
  }

  public boolean isCold() {
    return "cold".equals(tier);
  }

  public static String normalizeBulletId(String bulletId) {
    if (bulletId == null) {
      return "";
    }
    String trimmed = bulletId.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    String normalized = trimmed
        .replaceAll("[^A-Za-z0-9_-]+", "-")
        .replaceAll("-{2,}", "-")
        .replaceAll("^-+|-+$", "")
        .toLowerCase(Locale.ROOT);
    return normalized;
  }

  private static boolean isBulletId(String bulletId) {
    return bulletId != null && !bulletId.isEmpty();
  }

  private static String normalizeTier(String tier, int helpfulCount, int harmfulCount) {
    if (tier != null && !tier.isBlank()) {
      String normalized = tier.trim().toLowerCase();
      if (normalized.equals("hot") || normalized.equals("warm") || normalized.equals("cold")) {
        return normalized;
      }
    }
    if (harmfulCount >= helpfulCount + 2) {
      return "cold";
    }
    if (helpfulCount >= harmfulCount + 2) {
      return "hot";
    }
    return "warm";
  }
}
