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
      case CREATE -> "Create a new todo task. Use this when the user gives a multi-step task (3+ distinct steps) to break it down into manageable items. Each todo should represent one meaningful unit of work, not a trivial action.";
      case UPDATE -> "Update a todo task's status or content. Mark a task as in_progress BEFORE starting work on it, and completed IMMEDIATELY after finishing. Only ONE task in_progress at a time. Status must be: pending, in_progress, or completed.";
      case LIST -> "List current todo tasks, optionally filtered by status. Use this at the start of a turn to check what's left to do, or when the user asks about progress.";
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
        yield schema;
      }
    };
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) {
    TodoStore store = new TodoStore(context.cwd());
    return switch (kind) {
      case CREATE -> create(input, store);
      case UPDATE -> update(input, store);
      case LIST -> list(input, store);
    };
  }

  private static ToolResult create(JsonNode input, TodoStore store) {
    String content = JsonSchemas.text(input, "content", "");
    if (content.isBlank()) return ToolResult.error("content is required");
    String activeForm = JsonSchemas.text(input, "activeForm", content);
    TodoEntry entry = store.add(content, activeForm);
    return ToolResult.ok("Created todo " + entry.id() + ": " + entry.content() + " [" + entry.status() + "]");
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
    List<TodoEntry> todos = store.list(statusFilter.isBlank() ? null : statusFilter);
    if (todos.isEmpty()) return ToolResult.ok("(no todos)");
    StringBuilder out = new StringBuilder();
    for (TodoEntry t : todos) {
      String icon = switch (t.status()) {
        case "completed" -> "[x]";
        case "in_progress" -> "[>]";
        default -> "[ ]";
      };
      out.append(icon).append(" ").append(t.id()).append(": ").append(t.content()).append("\n");
    }
    return ToolResult.ok(out.toString().trim());
  }

  public enum Kind {
    CREATE,
    UPDATE,
    LIST
  }
}
