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
  private static final String USER_PROFILE_FILENAME = "user-profile.md";
  private static final int MAX_USER_PROFILE_CHARS = 4000;

  private final Path root;

  /**
   * When true, this store only manages user-profile.md — no individual .md files.
   * Default store (~/.codeauto/memory/) is profile-only.
   * Reflection/bullet stores (<project>/.codeauto/reflections/, bullets/) are not.
   */
  private final boolean profileStore;

  public MemoryManager() {
    this(RuntimeConfig.homeDir().resolve("memory"), true);
  }

  public MemoryManager(Path root) {
    this(root, false);
  }

  private MemoryManager(Path root, boolean profileStore) {
    this.root = root;
    this.profileStore = profileStore;
  }

  public Path root() {
    return root;
  }

  /**
   * In profile-store mode, always saves to user-profile.md (type parameter is ignored).
   * In file mode (reflections/bullets), saves as individual .md file.
   */
  public MemoryEntry save(MemoryType type, String title, Path project, List<String> tags, String content) {
    if (profileStore) {
      return saveUserProfile(title, project, tags, content);
    }
    return saveBullet(type, title, project, tags, content, "", "");
  }

  /**
   * Returns all USER-type entries from the single user-profile.md file.
   * Always returns the full profile — not subject to keyword filtering or top-N cutoff.
   */
  public List<MemoryEntry> loadUserProfile() {
    Path profilePath = root.resolve(USER_PROFILE_FILENAME);
    if (!Files.isRegularFile(profilePath)) return List.of();
    try {
      return parseUserProfile(Files.readString(profilePath), profilePath);
    } catch (Exception ignored) {
      return List.of();
    }
  }

  public List<MemoryEntry> list() {
    List<MemoryEntry> entries = new ArrayList<>();
    if (!Files.isDirectory(root)) return entries;
    if (profileStore) {
      // Profile store: only user-profile.md exists
      return loadUserProfile();
    }
    // File store (reflections/bullets): scan individual .md files
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

  public MemoryEntry saveBullet(MemoryType type, String title, Path project,
      List<String> tags, String content, String bulletId, String section) {
    try {
      Files.createDirectories(root);
      Instant now = Instant.now();
      String id = slug(title) + "-" + UUID.randomUUID().toString().substring(0, 8);
      String normalizedBulletId = MemoryEntry.normalizeBulletId(bulletId);
      if ((bulletId != null && !bulletId.isBlank()) && normalizedBulletId.isBlank()) {
        normalizedBulletId = "bullet-" + UUID.randomUUID().toString().substring(0, 8);
      }
      MemoryEntry entry = new MemoryEntry(
          id,
          type == null ? MemoryType.PROJECT : type,
          title == null || title.isBlank() ? "Untitled memory" : title.trim(),
          project == null ? "" : project.toAbsolutePath().normalize().toString(),
          tags == null ? List.of() : List.copyOf(tags),
          now,
          now,
          content == null ? "" : content.trim(),
          root.resolve(id + ".md"),
          normalizedBulletId,
          0,
          0,
          section == null ? "" : section,
          "warm",
          0,
          0,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH);
      write(entry);
      return entry;
    } catch (Exception error) {
      throw new IllegalStateException("Failed to save bullet memory: " + error.getMessage(), error);
    }
  }

  // ── User profile (single-file, section-based) ──────────────────────

  private MemoryEntry saveUserProfile(String title, Path project, List<String> tags, String content) {
    try {
      Files.createDirectories(root);
      Path profilePath = root.resolve(USER_PROFILE_FILENAME);
      Instant now = Instant.now();
      String id = "user-" + slug(title) + "-" + UUID.randomUUID().toString().substring(0, 8);
      String existingRaw = Files.isRegularFile(profilePath) ? Files.readString(profilePath) : "";

      // Check for duplicate by similar title → update existing section
      String existingId = findProfileSectionByTitle(existingRaw, title);
      boolean isUpdate = existingId != null;
      String effectiveId = isUpdate ? existingId : id;
      Instant createdAt = now;
      if (isUpdate) {
        MemoryEntry old = parseUserProfile(existingRaw, profilePath).stream()
            .filter(e -> e.id().equals(existingId))
            .findFirst().orElse(null);
        if (old != null) createdAt = old.createdAt();
      }

      String newSection = formatProfileSection(effectiveId, title, createdAt, now, tags, content);

      String newProfile;
      if (isUpdate) {
        newProfile = replaceProfileSection(existingRaw, existingId, newSection);
      } else {
        newProfile = existingRaw.strip();
        int newLen = newProfile.length() + (newProfile.isEmpty() ? 0 : 2) + newSection.length();
        if (newLen > MAX_USER_PROFILE_CHARS) {
          throw new IllegalStateException(
              "User profile would exceed " + MAX_USER_PROFILE_CHARS + " chars. "
              + "Delete some old preferences before adding new ones.");
        }
        newProfile = (newProfile.isEmpty() ? "" : newProfile + "\n\n") + newSection;
      }

      if (newProfile.length() > MAX_USER_PROFILE_CHARS) {
        throw new IllegalStateException(
            "User profile exceeds " + MAX_USER_PROFILE_CHARS + " char limit after update.");
      }

      Files.writeString(profilePath, newProfile.strip() + "\n");

      return new MemoryEntry(
          effectiveId, MemoryType.USER, title,
          project == null ? "" : project.toAbsolutePath().normalize().toString(),
          tags == null ? List.of() : List.copyOf(tags),
          createdAt, now, content == null ? "" : content.trim(),
          profilePath, "", 0, 0, "");
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception error) {
      throw new IllegalStateException("Failed to save user profile entry: " + error.getMessage(), error);
    }
  }

  private boolean deleteUserProfileEntry(String id) {
    Path profilePath = root.resolve(USER_PROFILE_FILENAME);
    if (!Files.isRegularFile(profilePath)) return false;
    try {
      String raw = Files.readString(profilePath);
      String updated = removeProfileSection(raw, id);
      if (updated == null) return false; // section not found
      Files.writeString(profilePath, updated.strip() + "\n");
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  // ── Parsing ────────────────────────────────────────────────────────

  private List<MemoryEntry> parseUserProfile(String raw, Path profilePath) {
    List<MemoryEntry> entries = new ArrayList<>();
    if (raw == null || raw.isBlank()) return entries;
    // Split on <!-- id:... --> marker that starts each section
    String[] blocks = raw.split("(?=<!-- id:)");
    for (String block : blocks) {
      String trimmed = block.strip();
      if (trimmed.isEmpty()) continue;
      MemoryEntry entry = parseProfileSection(trimmed, profilePath);
      if (entry != null) entries.add(entry);
    }
    return entries;
  }

  private MemoryEntry parseProfileSection(String block, Path profilePath) {
    // Format: <!-- id:xxx created:... updated:... tags:... -->\n## Title\nContent
    int commentEnd = block.indexOf("-->");
    if (commentEnd < 0) return null;
    Map<String, String> meta = parseProfileMeta(block.substring(4, commentEnd).trim());
    String id = meta.getOrDefault("id", "");
    if (id.isBlank()) return null;
    String rest = block.substring(commentEnd + 3).strip();
    int newlineIdx = rest.indexOf('\n');
    String title;
    String content;
    if (newlineIdx > 0) {
      String heading = rest.substring(0, newlineIdx).strip();
      title = heading.startsWith("## ") ? heading.substring(3).trim() : heading;
      content = rest.substring(newlineIdx + 1).strip();
    } else {
      title = rest.startsWith("## ") ? rest.substring(3).trim() : rest;
      content = "";
    }
    return new MemoryEntry(
        id, MemoryType.USER, title, "",
        splitTags(meta.getOrDefault("tags", "")),
        instant(meta.get("created")),
        instant(meta.get("updated")),
        content,
        profilePath,
        "", 0, 0, "");
  }

  private static Map<String, String> parseProfileMeta(String raw) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String part : raw.split("\\s+")) {
      int colon = part.indexOf(':');
      if (colon > 0) values.put(part.substring(0, colon), part.substring(colon + 1));
    }
    return values;
  }

  // ── Section manipulation ───────────────────────────────────────────

  private static String formatProfileSection(String id, String title, Instant createdAt,
      Instant updatedAt, List<String> tags, String content) {
    StringBuilder sb = new StringBuilder();
    sb.append("<!-- id:").append(id)
        .append(" created:").append(createdAt)
        .append(" updated:").append(updatedAt);
    if (tags != null && !tags.isEmpty()) {
      sb.append(" tags:").append(String.join(",", tags));
    }
    sb.append(" -->\n");
    sb.append("## ").append(title).append("\n\n");
    sb.append(content);
    return sb.toString();
  }

  private static String findProfileSectionByTitle(String raw, String title) {
    if (raw == null || raw.isBlank() || title == null || title.isBlank()) return null;
    String target = title.toLowerCase(Locale.ROOT).trim();
    for (String block : raw.split("(?=<!-- id:)")) {
      int commentEnd = block.indexOf("-->");
      if (commentEnd < 0) continue;
      Map<String, String> meta = parseProfileMeta(block.substring(4, commentEnd).trim());
      String rest = block.substring(commentEnd + 3).strip();
      String sectionTitle = extractSectionTitle(rest);
      if (sectionTitle != null && sectionTitle.toLowerCase(Locale.ROOT).trim().equals(target)) {
        return meta.get("id");
      }
    }
    return null;
  }

  private static String replaceProfileSection(String raw, String id, String newSection) {
    String[] blocks = raw.split("(?=<!-- id:)");
    StringBuilder sb = new StringBuilder();
    boolean replaced = false;
    for (String block : blocks) {
      String trimmed = block.strip();
      if (trimmed.isEmpty()) continue;
      if (!replaced && hasBlockId(trimmed, id)) {
        sb.append(newSection);
        replaced = true;
      } else {
        if (!sb.isEmpty()) sb.append("\n\n");
        sb.append(trimmed);
      }
    }
    return sb.toString();
  }

  private static String removeProfileSection(String raw, String id) {
    String[] blocks = raw.split("(?=<!-- id:)");
    StringBuilder sb = new StringBuilder();
    boolean found = false;
    for (String block : blocks) {
      String trimmed = block.strip();
      if (trimmed.isEmpty()) continue;
      if (hasBlockId(trimmed, id)) {
        found = true;
        continue;
      }
      if (!sb.isEmpty()) sb.append("\n\n");
      sb.append(trimmed);
    }
    return found ? sb.toString() : null;
  }

  private static boolean hasBlockId(String block, String id) {
    int commentEnd = block.indexOf("-->");
    if (commentEnd < 0) return false;
    Map<String, String> meta = parseProfileMeta(block.substring(4, commentEnd).trim());
    return id.equals(meta.get("id"));
  }

  private static String extractSectionTitle(String rest) {
    if (rest == null || rest.isBlank()) return null;
    String firstLine = rest.split("\\R", 2)[0].strip();
    return firstLine.startsWith("## ") ? firstLine.substring(3).trim() : null;
  }

  public void overwrite(MemoryEntry entry) {
    try {
      write(entry);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to overwrite memory: " + error.getMessage(), error);
    }
  }

  public boolean incrementCounters(String bulletId, int helpfulDelta, int harmfulDelta) {
    if (bulletId == null || bulletId.isBlank()) return false;
    java.util.Optional<MemoryEntry> found = list().stream()
        .filter(e -> e.bulletId().equals(bulletId) && e.isBullet())
        .findFirst();
    if (found.isEmpty()) return false;
    MemoryEntry entry = found.get();
    try {
      MemoryEntry updated = new MemoryEntry(
          entry.id(), entry.type(), entry.title(), entry.project(),
          entry.tags(), entry.createdAt(), Instant.now(), entry.content(),
          entry.path(), entry.bulletId(),
          entry.helpfulCount() + helpfulDelta,
          entry.harmfulCount() + harmfulDelta,
          entry.section(),
          deriveTier(entry.helpfulCount() + helpfulDelta, entry.harmfulCount() + harmfulDelta),
          entry.supportCount(),
          entry.retrieveCount(),
          entry.lastRetrievedAt(),
          entry.lastInjectedAt(),
          entry.lastSupportedAt(),
          helpfulDelta > 0 ? Instant.now() : entry.lastHelpfulAt(),
          harmfulDelta > 0 ? Instant.now() : entry.lastHarmfulAt());
      write(updated);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public boolean recordRetrieval(String bulletId) {
    return updateBulletUsage(bulletId, true, false);
  }

  public boolean recordInjection(String bulletId) {
    return updateBulletUsage(bulletId, false, true);
  }

  public boolean recordSupport(String bulletId, List<String> additionalTags) {
    if (bulletId == null || bulletId.isBlank()) return false;
    java.util.Optional<MemoryEntry> found = list().stream()
        .filter(e -> e.bulletId().equals(bulletId) && e.isBullet())
        .findFirst();
    if (found.isEmpty()) return false;
    MemoryEntry entry = found.get();
    Instant now = Instant.now();
    List<String> mergedTags = new ArrayList<>(entry.tags());
    if (additionalTags != null) {
      for (String tag : additionalTags) {
        if (tag != null && !tag.isBlank() && !mergedTags.contains(tag)) {
          mergedTags.add(tag);
        }
      }
    }
    try {
      MemoryEntry updated = new MemoryEntry(
          entry.id(), entry.type(), entry.title(), entry.project(),
          mergedTags, entry.createdAt(), now, entry.content(),
          entry.path(), entry.bulletId(),
          entry.helpfulCount(), entry.harmfulCount(), entry.section(),
          entry.tier(), entry.supportCount() + 1, entry.retrieveCount(),
          entry.lastRetrievedAt(), entry.lastInjectedAt(), now,
          entry.lastHelpfulAt(), entry.lastHarmfulAt());
      write(updated);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public boolean delete(String id) {
    if (id == null || id.isBlank()) return false;
    // USER entries live in user-profile.md sections, not standalone files
    if (id.startsWith("user-")) {
      return deleteUserProfileEntry(id);
    }
    try {
      return Files.deleteIfExists(root.resolve(id + ".md"));
    } catch (Exception error) {
      return false;
    }
  }

  private void write(MemoryEntry entry) throws Exception {
    if (entry.path().getParent() != null) {
      Files.createDirectories(entry.path().getParent());
    }
    StringBuilder out = new StringBuilder();
    out.append("---\n");
    out.append("id: ").append(entry.id()).append("\n");
    out.append("type: ").append(entry.type().name().toLowerCase(Locale.ROOT)).append("\n");
    out.append("title: ").append(escape(entry.title())).append("\n");
    out.append("project: ").append(escape(entry.project())).append("\n");
    out.append("tags: ").append(String.join(",", entry.tags())).append("\n");
    out.append("createdAt: ").append(entry.createdAt()).append("\n");
    out.append("updatedAt: ").append(entry.updatedAt()).append("\n");
    if (!entry.bulletId().isBlank()) {
      out.append("bulletId: ").append(escape(entry.bulletId())).append("\n");
      out.append("tier: ").append(entry.tier()).append("\n");
      out.append("supportCount: ").append(entry.supportCount()).append("\n");
      out.append("retrieveCount: ").append(entry.retrieveCount()).append("\n");
    }
    if (entry.isBullet() || entry.helpfulCount() > 0) {
      out.append("helpfulCount: ").append(Integer.toString(entry.helpfulCount())).append("\n");
    }
    if (entry.isBullet() || entry.harmfulCount() > 0) {
      out.append("harmfulCount: ").append(Integer.toString(entry.harmfulCount())).append("\n");
    }
    if (!entry.section().isBlank()) {
      out.append("section: ").append(escape(entry.section())).append("\n");
    }
    if (entry.isBullet() && !entry.lastRetrievedAt().equals(Instant.EPOCH)) {
      out.append("lastRetrievedAt: ").append(entry.lastRetrievedAt()).append("\n");
    }
    if (entry.isBullet() && !entry.lastInjectedAt().equals(Instant.EPOCH)) {
      out.append("lastInjectedAt: ").append(entry.lastInjectedAt()).append("\n");
    }
    if (entry.isBullet() && !entry.lastSupportedAt().equals(Instant.EPOCH)) {
      out.append("lastSupportedAt: ").append(entry.lastSupportedAt()).append("\n");
    }
    if (entry.isBullet() && !entry.lastHelpfulAt().equals(Instant.EPOCH)) {
      out.append("lastHelpfulAt: ").append(entry.lastHelpfulAt()).append("\n");
    }
    if (entry.isBullet() && !entry.lastHarmfulAt().equals(Instant.EPOCH)) {
      out.append("lastHarmfulAt: ").append(entry.lastHarmfulAt()).append("\n");
    }
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
      String bulletId = meta.getOrDefault("bulletId", "");
      int helpfulCount = parseIntSafe(meta.get("helpfulCount"));
      int harmfulCount = parseIntSafe(meta.get("harmfulCount"));
      String section = meta.getOrDefault("section", "");
      String tier = meta.getOrDefault("tier", "");
      int supportCount = parseIntSafe(meta.get("supportCount"));
      int retrieveCount = parseIntSafe(meta.get("retrieveCount"));
      return java.util.Optional.of(new MemoryEntry(
          meta.getOrDefault("id", stripExtension(path.getFileName().toString())),
          MemoryType.from(meta.get("type")),
          meta.getOrDefault("title", "Untitled memory"),
          meta.getOrDefault("project", ""),
          splitTags(meta.getOrDefault("tags", "")),
          instant(meta.get("createdAt")),
          instant(meta.get("updatedAt")),
          content,
          path,
          bulletId,
          helpfulCount,
          harmfulCount,
          section,
          tier,
          supportCount,
          retrieveCount,
          instant(meta.get("lastRetrievedAt")),
          instant(meta.get("lastInjectedAt")),
          instant(meta.get("lastSupportedAt")),
          instant(meta.get("lastHelpfulAt")),
          instant(meta.get("lastHarmfulAt"))));
    } catch (Exception ignored) {
      return java.util.Optional.empty();
    }
  }

  private boolean updateBulletUsage(String bulletId, boolean markRetrieved, boolean markInjected) {
    if (bulletId == null || bulletId.isBlank()) return false;
    java.util.Optional<MemoryEntry> found = list().stream()
        .filter(e -> e.bulletId().equals(bulletId) && e.isBullet())
        .findFirst();
    if (found.isEmpty()) return false;
    MemoryEntry entry = found.get();
    Instant now = Instant.now();
    try {
      MemoryEntry updated = new MemoryEntry(
          entry.id(), entry.type(), entry.title(), entry.project(),
          entry.tags(), entry.createdAt(), now, entry.content(),
          entry.path(), entry.bulletId(),
          entry.helpfulCount(), entry.harmfulCount(), entry.section(),
          entry.tier(), entry.supportCount(),
          markRetrieved ? entry.retrieveCount() + 1 : entry.retrieveCount(),
          markRetrieved ? now : entry.lastRetrievedAt(),
          markInjected ? now : entry.lastInjectedAt(),
          entry.lastSupportedAt(),
          entry.lastHelpfulAt(),
          entry.lastHarmfulAt());
      write(updated);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static String deriveTier(int helpfulCount, int harmfulCount) {
    if (harmfulCount >= helpfulCount + 2) {
      return "cold";
    }
    if (helpfulCount >= harmfulCount + 2) {
      return "hot";
    }
    return "warm";
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

  private static int parseIntSafe(String value) {
    if (value == null || value.isBlank()) return 0;
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return 0;
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
