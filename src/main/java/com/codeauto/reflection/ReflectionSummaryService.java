package com.codeauto.reflection;

import com.codeauto.memory.MemoryEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReflectionSummaryService {
  private static final int MAX_RECENT_REFLECTIONS = 5;
  private static final int MIN_SUPPORT_COUNT = 1;

  private final Path root;

  ReflectionSummaryService(Path root) {
    this.root = root;
  }

  void update(
      MemoryEntry bullet,
      MemoryEntry reflectionEntry,
      ReflectionService.ReflectionTrigger trigger,
      String reflectionText
  ) {
    if (bullet == null || !bullet.isBullet() || reflectionEntry == null || !shouldCreateFor(bullet)) {
      return;
    }
    try {
      Files.createDirectories(root);
      Path file = root.resolve(bullet.bulletId() + ".md");
      SummaryState previous = Files.isRegularFile(file) ? parse(file) : SummaryState.empty();
      Instant now = Instant.now();

      List<String> recentIds = new ArrayList<>(previous.recentReflectionIds());
      recentIds.remove(reflectionEntry.id());
      recentIds.add(reflectionEntry.id());
      while (recentIds.size() > MAX_RECENT_REFLECTIONS) {
        recentIds.removeFirst();
      }

      int baselineCount = Math.max(1, bullet.supportCount() + 1);
      SummaryState updated = new SummaryState(
          bullet.bulletId(),
          bullet.title(),
          bullet.project(),
          bullet.tags(),
          previous.createdAt().equals(Instant.EPOCH) ? now : previous.createdAt(),
          now,
          Math.max(previous.reflectionCount() + 1, baselineCount),
          reflectionEntry.id(),
          reflectionEntry.updatedAt(),
          recentIds);

      Files.writeString(file, render(updated, bullet, reflectionEntry, trigger, reflectionText));
    } catch (Exception ignored) {
      // Canonical summaries are best-effort and must never block reflection storage.
    }
  }

  private static boolean shouldCreateFor(MemoryEntry bullet) {
    return bullet.supportCount() >= MIN_SUPPORT_COUNT;
  }

  private static SummaryState parse(Path file) throws Exception {
    String raw = Files.readString(file);
    if (!raw.startsWith("---\n")) {
      return SummaryState.empty();
    }
    int end = raw.indexOf("\n---", 4);
    if (end < 0) {
      return SummaryState.empty();
    }
    Map<String, String> meta = new LinkedHashMap<>();
    for (String line : raw.substring(4, end).split("\\R")) {
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      meta.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
    }
    return new SummaryState(
        meta.getOrDefault("bulletId", ""),
        meta.getOrDefault("title", ""),
        meta.getOrDefault("project", ""),
        splitTags(meta.getOrDefault("tags", "")),
        instant(meta.get("createdAt")),
        instant(meta.get("updatedAt")),
        parseInt(meta.get("reflectionCount")),
        meta.getOrDefault("lastReflectionId", ""),
        instant(meta.get("lastReflectionAt")),
        splitTags(meta.getOrDefault("recentReflectionIds", "")));
  }

  private static String render(
      SummaryState summary,
      MemoryEntry bullet,
      MemoryEntry reflectionEntry,
      ReflectionService.ReflectionTrigger trigger,
      String reflectionText
  ) {
    StringBuilder out = new StringBuilder();
    out.append("---\n");
    out.append("bulletId: ").append(summary.bulletId()).append("\n");
    out.append("title: ").append(escape(summary.title())).append("\n");
    out.append("project: ").append(escape(summary.project())).append("\n");
    out.append("tags: ").append(String.join(",", summary.tags())).append("\n");
    out.append("createdAt: ").append(summary.createdAt()).append("\n");
    out.append("updatedAt: ").append(summary.updatedAt()).append("\n");
    out.append("reflectionCount: ").append(summary.reflectionCount()).append("\n");
    out.append("lastReflectionId: ").append(summary.lastReflectionId()).append("\n");
    out.append("lastReflectionAt: ").append(summary.lastReflectionAt()).append("\n");
    out.append("recentReflectionIds: ").append(String.join(",", summary.recentReflectionIds())).append("\n");
    out.append("---\n\n");

    out.append("# Canonical Reflection Summary\n\n");
    out.append("## Bullet\n");
    out.append("[bullet:").append(bullet.bulletId()).append("] ").append(bullet.title()).append("\n\n");

    out.append("## Canonical Lesson\n");
    out.append(bullet.content().trim()).append("\n\n");

    out.append("## Scope\n");
    out.append("- section: ").append(bullet.section()).append("\n");
    out.append("- tier: ").append(bullet.tier()).append("\n");
    out.append("- tags: ").append(String.join(", ", bullet.tags())).append("\n\n");

    out.append("## Evidence\n");
    out.append("- reflections: ").append(summary.reflectionCount()).append("\n");
    out.append("- supports: ").append(bullet.supportCount()).append("\n");
    out.append("- feedback: up ").append(bullet.helpfulCount())
        .append(", down ").append(bullet.harmfulCount()).append("\n\n");

    out.append("## Latest Reflection\n");
    out.append("- id: ").append(reflectionEntry.id()).append("\n");
    out.append("- title: ").append(reflectionEntry.title()).append("\n");
    out.append("- trigger: ").append(trigger.name().toLowerCase()).append("\n\n");

    appendSection(out, "What Went Wrong", extractSection(reflectionText, "What Went Wrong"));
    appendSection(out, "Root Cause", extractSection(reflectionText, "Root Cause"));
    appendSection(out, "Better Approach", extractSection(reflectionText, "What Should Have Been Done Differently"));

    out.append("## Recent Reflection IDs\n");
    for (String id : summary.recentReflectionIds()) {
      out.append("- ").append(id).append("\n");
    }
    return out.toString();
  }

  private static void appendSection(StringBuilder out, String title, String content) {
    out.append("## ").append(title).append("\n");
    out.append(content.isBlank() ? "Nothing." : content).append("\n\n");
  }

  private static String extractSection(String reflection, String section) {
    int start = reflection.indexOf("### " + section);
    if (start < 0) {
      return "";
    }
    int headingEnd = reflection.indexOf('\n', start);
    if (headingEnd < 0) {
      return "";
    }
    int nextHeading = reflection.indexOf("\n###", headingEnd + 1);
    String body = nextHeading > 0
        ? reflection.substring(headingEnd + 1, nextHeading)
        : reflection.substring(headingEnd + 1);
    return body.strip();
  }

  private static List<String> splitTags(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    List<String> tags = new ArrayList<>();
    for (String part : raw.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        tags.add(trimmed);
      }
    }
    return tags;
  }

  private static int parseInt(String raw) {
    if (raw == null || raw.isBlank()) return 0;
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static Instant instant(String raw) {
    try {
      return raw == null || raw.isBlank() ? Instant.EPOCH : Instant.parse(raw.trim());
    } catch (Exception ignored) {
      return Instant.EPOCH;
    }
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace('\n', ' ').trim();
  }

  private record SummaryState(
      String bulletId,
      String title,
      String project,
      List<String> tags,
      Instant createdAt,
      Instant updatedAt,
      int reflectionCount,
      String lastReflectionId,
      Instant lastReflectionAt,
      List<String> recentReflectionIds
  ) {
    private static SummaryState empty() {
      return new SummaryState(
          "", "", "", List.of(), Instant.EPOCH, Instant.EPOCH, 0, "", Instant.EPOCH, List.of());
    }
  }
}
