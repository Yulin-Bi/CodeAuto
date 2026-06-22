package com.codeauto.todo;

import com.codeauto.config.RuntimeConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TodoStore {
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .registerModule(new JavaTimeModule());
  private static final TypeReference<List<TodoEntry>> LIST_TYPE = new TypeReference<>() {
  };
  private static final int PROMPT_GROUP_LIMIT = 3;
  private static final int PROMPT_ITEMS_PER_STATE = 3;
  private static final int GROUP_TITLE_MAX_CHARS = 64;

  private final Path cwd;

  public TodoStore(Path cwd) {
    this.cwd = cwd.toAbsolutePath().normalize();
  }

  public TodoEntry add(String content, String activeForm) {
    return add(content, activeForm, null, null, null);
  }

  public TodoEntry add(
      String content,
      String activeForm,
      String groupId,
      String groupTitle,
      String turnId) {
    List<TodoEntry> todos = load();
    Instant now = Instant.now();
    String id = UUID.randomUUID().toString().substring(0, 8);
    String resolvedGroupId = resolveGroupId(groupId, turnId, id);
    String resolvedGroupTitle = resolveGroupTitle(todos, resolvedGroupId, groupTitle, content);
    TodoEntry entry = new TodoEntry(
        id,
        content.trim(),
        "pending",
        activeForm == null || activeForm.isBlank() ? content.trim() : activeForm.trim(),
        resolvedGroupId,
        resolvedGroupTitle,
        now,
        now);
    todos.add(entry);
    save(todos);
    return entry;
  }

  public TodoEntry update(String id, String status, String content) {
    List<TodoEntry> todos = load();
    for (int i = 0; i < todos.size(); i++) {
      TodoEntry entry = todos.get(i);
      if (entry.id().equals(id)) {
        TodoEntry updated = new TodoEntry(
            entry.id(),
            content != null && !content.isBlank() ? content.trim() : entry.content(),
            status != null && !status.isBlank() ? status : entry.status(),
            entry.activeForm(),
            entry.groupId(),
            entry.groupTitle(),
            entry.createdAt(),
            Instant.now());
        todos.set(i, updated);
        save(todos);
        return updated;
      }
    }
    return null;
  }

  public boolean delete(String id) {
    List<TodoEntry> todos = load();
    boolean removed = todos.removeIf(entry -> entry.id().equals(id));
    if (removed) save(todos);
    return removed;
  }

  public List<TodoEntry> list(String statusFilter) {
    List<TodoEntry> todos = load();
    if (statusFilter == null || statusFilter.isBlank()) {
      return todos;
    }
    return todos.stream()
        .filter(e -> e.status().equals(statusFilter))
        .toList();
  }

  public List<TodoGroup> groups() {
    return groupTodos(load());
  }

  public List<TodoGroup> recentActiveGroups() {
    return groups().stream()
        .filter(TodoGroup::hasActiveItems)
        .limit(PROMPT_GROUP_LIMIT)
        .toList();
  }

  public int clearCompleted() {
    List<TodoEntry> todos = load();
    int removed = todos.size();
    List<TodoEntry> remaining = todos.stream()
        .filter(e -> !"completed".equals(e.status()))
        .toList();
    removed -= remaining.size();
    if (removed > 0) save(new ArrayList<>(remaining));
    return removed;
  }

  public String summary() {
    return promptContext();
  }

  public String promptContext() {
    List<TodoGroup> groups = recentActiveGroups();
    if (groups.isEmpty()) {
      return "";
    }
    StringBuilder summary = new StringBuilder();
    summary.append("Active todo groups. Keep them in sync until every item is completed.\n");
    for (TodoGroup group : groups) {
      summary.append("- ")
          .append(group.title())
          .append(" [groupId=")
          .append(group.id())
          .append("]")
          .append(" (")
          .append(group.inProgressCount()).append(" in progress, ")
          .append(group.pendingCount()).append(" pending, ")
          .append(group.completedCount()).append(" completed)")
          .append("\n");
      appendStateLine(summary, "now", group.entries(), "in_progress", true);
      appendStateLine(summary, "pending", group.entries(), "pending", false);
      appendStateLine(summary, "done", group.entries(), "completed", false);
    }
    summary.append("Use todo_list for full grouped state. Reuse groupId for follow-up items.");
    return summary.toString().trim();
  }

  public List<String> activeContextTexts() {
    return recentActiveGroups().stream()
        .flatMap(group -> group.entries().stream())
        .filter(t -> "pending".equals(t.status()) || "in_progress".equals(t.status()))
        .flatMap(t -> java.util.stream.Stream.of(groupContextText(t), t.content(), t.activeForm()))
        .filter(text -> text != null && !text.isBlank())
        .map(text -> text.replaceAll("\\s+", " ").trim())
        .distinct()
        .toList();
  }

  public Set<String> activeGroupIds() {
    return new LinkedHashSet<>(recentActiveGroups().stream()
        .map(TodoGroup::id)
        .toList());
  }

  public static TodoStore forProject(Path cwd) {
    return new TodoStore(cwd);
  }

  public static List<TodoGroup> groupTodos(List<TodoEntry> todos) {
    if (todos == null || todos.isEmpty()) {
      return List.of();
    }
    Map<String, List<TodoEntry>> grouped = new LinkedHashMap<>();
    for (TodoEntry todo : todos.stream()
        .sorted(Comparator.comparing(TodoEntry::createdAt).thenComparing(TodoEntry::id))
        .toList()) {
      grouped.computeIfAbsent(effectiveGroupId(todo), ignored -> new ArrayList<>()).add(todo);
    }

    return grouped.entrySet().stream()
        .map(entry -> buildGroup(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparing(TodoGroup::updatedAt, Comparator.reverseOrder())
            .thenComparing(TodoGroup::createdAt, Comparator.reverseOrder()))
        .toList();
  }

  private List<TodoEntry> load() {
    Path file = todoFile();
    if (!Files.isRegularFile(file)) return new ArrayList<>();
    try {
      byte[] raw = Files.readAllBytes(file);
      if (raw.length == 0) return new ArrayList<>();
      List<TodoEntry> todos = MAPPER.readValue(raw, LIST_TYPE);
      return todos == null ? new ArrayList<>() : new ArrayList<>(todos);
    } catch (Exception ignored) {
      return new ArrayList<>();
    }
  }

  private void save(List<TodoEntry> todos) {
    try {
      Path file = todoFile();
      Files.createDirectories(file.getParent());
      MAPPER.writeValue(file.toFile(), todos);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to save todos: " + error.getMessage(), error);
    }
  }

  private Path todoFile() {
    String project = cwd.toString().replaceAll("[/\\\\:]+", "-").replaceAll("^-+", "");
    return RuntimeConfig.homeDir().resolve("todos").resolve(project + ".json");
  }

  private static TodoGroup buildGroup(String groupId, List<TodoEntry> rawEntries) {
    List<TodoEntry> entries = rawEntries.stream()
        .sorted(Comparator.comparingInt(TodoStore::priority)
            .thenComparing(TodoEntry::createdAt)
            .thenComparing(TodoEntry::updatedAt, Comparator.reverseOrder()))
        .toList();
    Instant createdAt = rawEntries.stream()
        .map(TodoEntry::createdAt)
        .min(Instant::compareTo)
        .orElse(Instant.EPOCH);
    Instant updatedAt = rawEntries.stream()
        .map(TodoEntry::updatedAt)
        .max(Instant::compareTo)
        .orElse(createdAt);
    String title = rawEntries.stream()
        .map(TodoEntry::groupTitle)
        .filter(text -> text != null && !text.isBlank())
        .findFirst()
        .orElseGet(() -> truncate(rawEntries.getFirst().content(), GROUP_TITLE_MAX_CHARS));
    long pending = rawEntries.stream().filter(t -> "pending".equals(t.status())).count();
    long inProgress = rawEntries.stream().filter(t -> "in_progress".equals(t.status())).count();
    long completed = rawEntries.stream().filter(t -> "completed".equals(t.status())).count();
    return new TodoGroup(groupId, title, entries, createdAt, updatedAt, pending, inProgress, completed);
  }

  private static void appendStateLine(
      StringBuilder summary,
      String label,
      List<TodoEntry> entries,
      String status,
      boolean preferActiveForm) {
    List<String> items = entries.stream()
        .filter(entry -> status.equals(entry.status()))
        .limit(PROMPT_ITEMS_PER_STATE)
        .map(entry -> textForSummary(entry, preferActiveForm))
        .toList();
    if (items.isEmpty()) {
      return;
    }
    summary.append("  ")
        .append(label)
        .append(": ")
        .append(String.join(" | ", items))
        .append("\n");
  }

  private static String resolveGroupId(String groupId, String turnId, String fallbackId) {
    if (groupId != null && !groupId.isBlank()) {
      return normalizeGroupId(groupId);
    }
    if (turnId != null && !turnId.isBlank()) {
      String normalizedTurnId = normalizeGroupId(turnId);
      return normalizedTurnId.startsWith("turn-") ? normalizedTurnId : "turn-" + normalizedTurnId;
    }
    return "task-" + normalizeGroupId(fallbackId);
  }

  private static String resolveGroupTitle(
      List<TodoEntry> todos,
      String groupId,
      String groupTitle,
      String content) {
    if (groupTitle != null && !groupTitle.isBlank()) {
      return truncate(groupTitle.trim(), GROUP_TITLE_MAX_CHARS);
    }
    return todos.stream()
        .filter(todo -> effectiveGroupId(todo).equals(groupId))
        .map(TodoEntry::groupTitle)
        .filter(title -> title != null && !title.isBlank())
        .findFirst()
        .orElseGet(() -> truncate(content == null ? "" : content.trim(), GROUP_TITLE_MAX_CHARS));
  }

  private static String normalizeGroupId(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9._-]+", "-")
        .replaceAll("^-+", "")
        .replaceAll("-+$", "");
    return normalized.isBlank() ? "task" : normalized;
  }

  private static String effectiveGroupId(TodoEntry entry) {
    if (entry.groupId() != null && !entry.groupId().isBlank()) {
      return entry.groupId();
    }
    return "legacy-" + entry.id();
  }

  private static int priority(TodoEntry entry) {
    return switch (entry.status()) {
      case "in_progress" -> 0;
      case "pending" -> 1;
      default -> 2;
    };
  }

  private static String textForSummary(TodoEntry entry, boolean preferActiveForm) {
    String text = preferActiveForm && entry.activeForm() != null && !entry.activeForm().isBlank()
        ? entry.activeForm()
        : entry.content();
    return truncate(text, 60);
  }

  private static String groupContextText(TodoEntry entry) {
    if (entry.groupTitle() == null || entry.groupTitle().isBlank()) {
      return entry.groupId();
    }
    return entry.groupTitle();
  }

  private static String truncate(String value, int maxChars) {
    if (value == null) return "";
    String normalized = value.replaceAll("\\s+", " ").trim();
    if (normalized.length() <= maxChars) {
      return normalized;
    }
    return normalized.substring(0, Math.max(0, maxChars - 3)) + "...";
  }

  public record TodoGroup(
      String id,
      String title,
      List<TodoEntry> entries,
      Instant createdAt,
      Instant updatedAt,
      long pendingCount,
      long inProgressCount,
      long completedCount) {

    public boolean hasActiveItems() {
      return pendingCount > 0 || inProgressCount > 0;
    }

    public long totalCount() {
      return entries == null ? 0 : entries.size();
    }
  }
}
