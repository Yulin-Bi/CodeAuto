package com.codeauto.todo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.codeauto.config.RuntimeConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class TodoStore {
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .registerModule(new JavaTimeModule());
  private static final int SUMMARY_NEXT_LIMIT = 2;
  private static final TypeReference<List<TodoEntry>> LIST_TYPE = new TypeReference<>() {
  };

  private final Path cwd;

  public TodoStore(Path cwd) {
    this.cwd = cwd.toAbsolutePath().normalize();
  }

  public TodoEntry add(String content, String activeForm) {
    List<TodoEntry> todos = load();
    Instant now = Instant.now();
    String id = UUID.randomUUID().toString().substring(0, 8);
    TodoEntry entry = new TodoEntry(
        id,
        content.trim(),
        "pending",
        activeForm == null || activeForm.isBlank() ? content.trim() : activeForm.trim(),
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
    List<TodoEntry> todos = load();
    if (todos.isEmpty()) return "";
    long pending = todos.stream().filter(t -> "pending".equals(t.status())).count();
    long inProgress = todos.stream().filter(t -> "in_progress".equals(t.status())).count();
    long active = pending + inProgress;
    if (active == 0) return "";
    List<TodoEntry> ordered = todos.stream()
        .filter(t -> "pending".equals(t.status()) || "in_progress".equals(t.status()))
        .sorted(Comparator.comparingInt(TodoStore::priority)
            .thenComparing(TodoEntry::createdAt)
            .thenComparing(TodoEntry::updatedAt, Comparator.reverseOrder()))
        .toList();

    StringBuilder summary = new StringBuilder();
    summary.append(active)
        .append(" unfinished todos (")
        .append(pending)
        .append(" pending, ")
        .append(inProgress)
        .append(" in progress).");

    ordered.stream()
        .filter(t -> "in_progress".equals(t.status()))
        .findFirst()
        .ifPresent(current -> summary.append(" Current: ")
            .append(textForSummary(current, true))
            .append("."));

    List<String> nextItems = ordered.stream()
        .filter(t -> !"in_progress".equals(t.status()))
        .limit(SUMMARY_NEXT_LIMIT)
        .map(t -> textForSummary(t, false))
        .toList();
    if (!nextItems.isEmpty()) {
      summary.append(" Next: ").append(String.join(" | ", nextItems)).append(".");
    }

    summary.append(" Call todo_list to review them and ask the user whether to continue or mark completed.");
    return summary.toString();
  }

  public List<String> activeContextTexts() {
    return load().stream()
        .filter(t -> "pending".equals(t.status()) || "in_progress".equals(t.status()))
        .flatMap(t -> java.util.stream.Stream.of(t.content(), t.activeForm()))
        .filter(text -> text != null && !text.isBlank())
        .map(text -> text.replaceAll("\\s+", " ").trim())
        .distinct()
        .toList();
  }

  public static TodoStore forProject(Path cwd) {
    return new TodoStore(cwd);
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
    return truncate(text, 48);
  }

  private static String truncate(String value, int maxChars) {
    if (value == null) return "";
    String normalized = value.replaceAll("\\s+", " ").trim();
    if (normalized.length() <= maxChars) {
      return normalized;
    }
    return normalized.substring(0, Math.max(0, maxChars - 3)) + "...";
  }
}
