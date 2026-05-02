package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.background.BackgroundTask;
import com.codeauto.background.BackgroundTaskRegistry;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;

public class BackgroundTasksTool implements ToolDefinition {
  @Override public String name() { return "background_tasks"; }
  @Override public String description() { return "List, inspect, or cancel background shell tasks."; }
  @Override public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    props.set("operation", JsonSchemas.stringProp("Operation: list, inspect, or cancel (default: list)"));
    props.set("task_id", JsonSchemas.stringProp("Task ID (required for inspect/cancel)"));
    return schema;
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) {
    String operation = input != null && input.has("operation")
        ? input.path("operation").asText("list")
        : "list";
    return switch (operation) {
      case "cancel" -> cancelTask(input);
      case "inspect" -> inspectTask(input);
      default -> listTasks();
    };
  }

  private static ToolResult listTasks() {
    var tasks = BackgroundTaskRegistry.get().list();
    if (tasks.isEmpty()) return ToolResult.ok("(none)");
    StringBuilder out = new StringBuilder();
    for (var task : tasks) {
      appendTask(out, task);
      if (!task.output().isBlank()) {
        out.append("  output: ").append(excerpt(task.output(), 200)).append("\n");
      }
    }
    return ToolResult.ok(out.toString().trim());
  }

  private static ToolResult inspectTask(JsonNode input) {
    String taskId = input != null ? input.path("task_id").asText("") : "";
    if (taskId.isBlank()) return ToolResult.error("task_id is required");
    BackgroundTask task = BackgroundTaskRegistry.get().get(taskId);
    if (task == null) return ToolResult.error("Task not found: " + taskId);
    StringBuilder out = new StringBuilder();
    appendTask(out, task);
    if (!task.output().isBlank()) {
      out.append("  output: ").append(task.output()).append("\n");
    }
    return ToolResult.ok(out.toString().trim());
  }

  private static ToolResult cancelTask(JsonNode input) {
    String taskId = input != null ? input.path("task_id").asText("") : "";
    if (taskId.isBlank()) return ToolResult.error("task_id is required");
    boolean killed = BackgroundTaskRegistry.get().kill(taskId);
    if (!killed) return ToolResult.error("Task not found: " + taskId);
    BackgroundTask task = BackgroundTaskRegistry.get().get(taskId);
    return ToolResult.ok("Cancelled task " + taskId + " (" + task.status() + ")");
  }

  private static void appendTask(StringBuilder out, BackgroundTask task) {
    out.append(task.id())
        .append(" pid=").append(task.pid())
        .append(" status=").append(task.status())
        .append(" started=").append(task.startedAt())
        .append(" command=").append(task.command())
        .append("\n");
  }

  private static String excerpt(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "...";
  }
}
