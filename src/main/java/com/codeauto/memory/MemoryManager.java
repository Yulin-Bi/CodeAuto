package com.codeauto.memory;

import com.codeauto.config.RuntimeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MemoryManager {
  private final Path root;

  public MemoryManager() {
    this(RuntimeConfig.homeDir().resolve("memory"));
  }

  public MemoryManager(Path root) {
    this.root = root;
  }

  public Path root() {
    return root;
  }

  public MemoryEntry save(MemoryType type, String title, Path project, List<String> tags, String content) {
    try {
      Files.createDirectories(root);
      Instant now = Instant.now();
      String id = slug(title) + "-" + UUID.randomUUID().toString().substring(0, 8);
      MemoryEntry entry = new MemoryEntry(
          id,
          type == null ? MemoryType.PROJECT : type,
          title == null || title.isBlank() ? "Untitled memory" : title.trim(),
          project == null ? "" : project.toAbsolutePath().normalize().toString(),
          tags == null ? List.of() : List.copyOf(tags),
          now,
          now,
          content == null ? "" : content.trim(),
          root.resolve(id + ".md"));
      write(entry);
      return entry;
    } catch (Exception error) {
      throw new IllegalStateException("Failed to save memory: " + error.getMessage(), error);
    }
  }

  public List<MemoryEntry> list() {
    List<MemoryEntry> entries = new ArrayList<>();
    if (!Files.isDirectory(root)) return entries;
    try (var paths = Files.list(root)) {
      for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
        parse(path).ifPresent(entries::add);
      }
    } catch (Exception ignored) {
      // Memory is optional and must never block prompt construction.
    }
    entries.sort(Comparator.comparing(MemoryEntry::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
    return entries;
  }

  public List<MemoryEntry> relevant(Path cwd, String query, int limit) {
    String project = cwd == null ? "" : cwd.toAbsolutePath().normalize().toString();
    String projectName = cwd == null || cwd.getFileName() == null ? "" : cwd.getFileName().toString();
    List<String> terms = terms((query == null || query.isBlank()) ? projectName : query + " " + projectName);
    return list().stream()
        .map(entry -> new ScoredMemory(entry, score(entry, project, terms)))
        .filter(scored -> scored.score() > 0)
        .sorted(Comparator.comparingInt(ScoredMemory::score).reversed()
            .thenComparing(scored -> scored.entry().updatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(Math.max(0, limit))
        .map(ScoredMemory::entry)
        .toList();
  }

  public boolean delete(String id) {
    if (id == null || id.isBlank()) return false;
    try {
      return Files.deleteIfExists(root.resolve(id + ".md"));
    } catch (Exception error) {
      return false;
    }
  }

  private void write(MemoryEntry entry) throws Exception {
    StringBuilder out = new StringBuilder();
    out.append("---\n");
    out.append("id: ").append(entry.id()).append("\n");
    out.append("type: ").append(entry.type().name().toLowerCase(Locale.ROOT)).append("\n");
    out.append("title: ").append(escape(entry.title())).append("\n");
    out.append("project: ").append(escape(entry.project())).append("\n");
    out.append("tags: ").append(String.join(",", entry.tags())).append("\n");
    out.append("createdAt: ").append(entry.createdAt()).append("\n");
    out.append("updatedAt: ").append(entry.updatedAt()).append("\n");
    out.append("---\n\n");
    out.append(entry.content()).append("\n");
    Files.writeString(entry.path(), out.toString());
  }

  private java.util.Optional<MemoryEntry> parse(Path path) {
    try {
      String raw = Files.readString(path);
      if (!raw.startsWith("---\n")) return java.util.Optional.empty();
      int end = raw.indexOf("\n---", 4);
      if (end < 0) return java.util.Optional.empty();
      Map<String, String> meta = parseFrontmatter(raw.substring(4, end));
      String content = raw.substring(end + 4).strip();
      return java.util.Optional.of(new MemoryEntry(
          meta.getOrDefault("id", stripExtension(path.getFileName().toString())),
          MemoryType.from(meta.get("type")),
          meta.getOrDefault("title", "Untitled memory"),
          meta.getOrDefault("project", ""),
          splitTags(meta.getOrDefault("tags", "")),
          instant(meta.get("createdAt")),
          instant(meta.get("updatedAt")),
          content,
          path));
    } catch (Exception ignored) {
      return java.util.Optional.empty();
    }
  }

  private static Map<String, String> parseFrontmatter(String raw) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String line : raw.split("\\R")) {
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      values.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
    }
    return values;
  }

  private static int score(MemoryEntry entry, String project, List<String> terms) {
    int score = 0;
    if (entry.project().isBlank()) score += 1;
    if (!project.isBlank() && entry.project().equals(project)) score += 8;
    String haystack = (entry.title() + " " + entry.tags() + " " + entry.content()).toLowerCase(Locale.ROOT);
    for (String term : terms) {
      if (haystack.contains(term)) score += 2;
    }
    return score;
  }

  private static List<String> terms(String value) {
    List<String> result = new ArrayList<>();
    for (String part : value.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}_-]+")) {
      if (part.length() >= 3) result.add(part);
    }
    return result;
  }

  private static List<String> splitTags(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    List<String> tags = new ArrayList<>();
    for (String tag : raw.split(",")) {
      if (!tag.trim().isBlank()) tags.add(tag.trim());
    }
    return tags;
  }

  private static Instant instant(String value) {
    try {
      return value == null || value.isBlank() ? Instant.EPOCH : Instant.parse(value.trim());
    } catch (Exception error) {
      return Instant.EPOCH;
    }
  }

  private static String slug(String value) {
    String source = value == null || value.isBlank() ? "memory" : value.toLowerCase(Locale.ROOT);
    String slug = source.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    return slug.isBlank() ? "memory" : slug.substring(0, Math.min(slug.length(), 40));
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace('\n', ' ').trim();
  }

  private static String stripExtension(String value) {
    int dot = value.lastIndexOf('.');
    return dot <= 0 ? value : value.substring(0, dot);
  }

  private record ScoredMemory(MemoryEntry entry, int score) {
  }
}
