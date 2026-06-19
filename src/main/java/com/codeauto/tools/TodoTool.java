package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.todo.TodoEntry;
import com.codeauto.todo.TodoStore;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;

import java.util.List;

public class TodoTool implements ToolDefinition {
  private final Kind kind;

  public TodoTool(Kind kind) {
    this.kind = kind;
  }

  @Override
  public String name() {
    return switch (kind) {
      case CREATE -> "todo_create";
      case UPDATE -> "todo_update";
      case LIST -> "todo_list";
    };
  }

  @Override
  public String description() {
    return switch (kind) {
      case CREATE -> "Create a new todo task. Use this when the user gives a multi-step task (3+ distinct steps) to break it down into manageable items. Keep items for the same user task in one todo group. groupTitle should describe the overall plan. Reuse groupId when you extend an unfinished plan in later turns.";
      case UPDATE -> "Update a todo task's status or content. Mark a task as in_progress BEFORE starting work on it, and completed IMMEDIATELY after finishing. Only ONE task in_progress at a time. Status must be: pending, in_progress, or completed.";
      case LIST -> "List todo tasks grouped by plan. By default this focuses on recent unfinished groups. Use scope=all when the user asks about older or completed work.";
    };
  }

  @Override
  public JsonNode inputSchema() {
    return switch (kind) {
      case CREATE -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("content", JsonSchemas.stringProp("Task description (imperative form, e.g. 'Add dark mode toggle')"));
        props.set("activeForm", JsonSchemas.stringProp(
            "Present continuous form (e.g. 'Adding dark mode toggle'). Defaults to content if omitted."));
        props.set("groupId", JsonSchemas.stringProp(
            "Optional stable plan/group id. Reuse this when adding more items to an unfinished plan in a later turn."));
        props.set("groupTitle", JsonSchemas.stringProp(
            "Optional concise plan title describing the whole task group, e.g. 'Polish TUI todo panel'."));
        yield JsonSchemas.required(schema, "content");
      }
      case UPDATE -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("id", JsonSchemas.stringProp("Todo task id to update"));
        props.set("status", JsonSchemas.stringProp("New status: pending, in_progress, or completed"));
        props.set("content", JsonSchemas.stringProp("Updated task description (optional)"));
        yield JsonSchemas.required(schema, "id");
      }
      case LIST -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("status", JsonSchemas.stringProp("Filter by status: pending, in_progress, or completed. Omit for all."));
        props.set("scope", JsonSchemas.stringProp("Scope: active (default) or all."));
        yield schema;
      }
    };
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) {
    TodoStore store = new TodoStore(context.cwd());
    return switch (kind) {
      case CREATE -> create(input, store, context);
      case UPDATE -> update(input, store);
      case LIST -> list(input, store);
    };
  }

  private static ToolResult create(JsonNode input, TodoStore store, ToolContext context) {
    String content = JsonSchemas.text(input, "content", "");
    if (content.isBlank()) return ToolResult.error("content is required");
    String activeForm = JsonSchemas.text(input, "activeForm", content);
    String groupId = JsonSchemas.text(input, "groupId", "");
    String groupTitle = JsonSchemas.text(input, "groupTitle", "");
    TodoEntry entry = store.add(
        content,
        activeForm,
        groupId.isBlank() ? null : groupId,
        groupTitle.isBlank() ? null : groupTitle,
        context.turnId());
    return ToolResult.ok("Created todo " + entry.id()
        + " in group " + entry.groupId()
        + " (" + entry.groupTitle() + "): "
        + entry.content() + " [" + entry.status() + "]");
  }

  private static ToolResult update(JsonNode input, TodoStore store) {
    String id = JsonSchemas.text(input, "id", "");
    if (id.isBlank()) return ToolResult.error("id is required");
    String status = JsonSchemas.text(input, "status", "");
    if (!status.isBlank() && !List.of("pending", "in_progress", "completed").contains(status)) {
      return ToolResult.error("status must be: pending, in_progress, or completed");
    }
    String content = JsonSchemas.text(input, "content", "");
    TodoEntry updated = store.update(id, status.isBlank() ? null : status,
        content.isBlank() ? null : content);
    if (updated == null) return ToolResult.error("Todo not found: " + id);
    return ToolResult.ok("Updated todo " + id + " -> " + updated.content() + " [" + updated.status() + "]");
  }

  private static ToolResult list(JsonNode input, TodoStore store) {
    String statusFilter = JsonSchemas.text(input, "status", "");
    String scope = JsonSchemas.text(input, "scope", "active");
    boolean includeAll = "all".equalsIgnoreCase(scope);
    List<com.codeauto.todo.TodoStore.TodoGroup> groups = includeAll ? store.groups() : store.recentActiveGroups();
    if (!statusFilter.isBlank()) {
      groups = groups.stream()
          .map(group -> new com.codeauto.todo.TodoStore.TodoGroup(
              group.id(),
              group.title(),
              group.entries().stream().filter(todo -> statusFilter.equals(todo.status())).toList(),
              group.createdAt(),
              group.updatedAt(),
              group.entries().stream().filter(todo -> "pending".equals(todo.status())).count(),
              group.entries().stream().filter(todo -> "in_progress".equals(todo.status())).count(),
              group.entries().stream().filter(todo -> "completed".equals(todo.status())).count()))
          .filter(group -> !group.entries().isEmpty())
          .toList();
    }
    if (groups.isEmpty()) return ToolResult.ok("(no todos)");
    StringBuilder out = new StringBuilder();
    int pending = 0;
    int inProgress = 0;
    int completed = 0;
    for (var group : groups) {
      String groupState = group.hasActiveItems() ? "active" : "completed";
      out.append("Group ").append(group.title())
          .append(" [groupId=").append(group.id()).append("] ")
          .append(groupState)
          .append(" (")
          .append(group.inProgressCount()).append(" in progress, ")
          .append(group.pendingCount()).append(" pending, ")
          .append(group.completedCount()).append(" completed)")
          .append("\n");
      for (TodoEntry t : group.entries()) {
        String icon = switch (t.status()) {
          case "completed" -> {
            completed++;
            yield "[x]";
          }
          case "in_progress" -> {
            inProgress++;
            yield "[>]";
          }
          default -> {
            pending++;
            yield "[ ]";
          }
        };
        out.append("  ").append(icon).append(" ").append(t.id()).append(": ").append(t.content()).append("\n");
      }
    }
    out.append("Summary: ")
        .append(groups.size())
        .append(" groups, ")
        .append(pending + inProgress + completed)
        .append(" todos (")
        .append(pending).append(" pending, ")
        .append(inProgress).append(" in progress, ")
        .append(completed).append(" completed)");
    return ToolResult.ok(out.toString().trim());
  }

  public enum Kind {
    CREATE,
    UPDATE,
    LIST
  }
}
