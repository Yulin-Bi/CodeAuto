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
    props.set("operation", JsonSchemas.stringProp("Operation: list, inspect, cancel, or restart (default: list)"));
    props.set("task_id", JsonSchemas.stringProp("Task ID (for inspect/cancel/restart)"));
    props.set("app_id", JsonSchemas.stringProp("Managed app ID (for inspect/cancel/restart)"));
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
      case "restart" -> restartTask(input, context);
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
    BackgroundTask task = resolveTask(input);
    String label = resolveLabel(input);
    if (label == null || label.isBlank()) return ToolResult.error("task_id or app_id is required");
    if (task == null) return ToolResult.error("Task not found: " + label);
    StringBuilder out = new StringBuilder();
    appendTask(out, task);
    if (!task.output().isBlank()) {
      out.append("  output: ").append(task.output()).append("\n");
    }
    return ToolResult.ok(out.toString().trim());
  }

  private static ToolResult cancelTask(JsonNode input) {
    String taskId = input != null ? input.path("task_id").asText("") : "";
    String appId = input != null ? input.path("app_id").asText("") : "";
    if (taskId.isBlank() && appId.isBlank()) return ToolResult.error("task_id or app_id is required");
    boolean killed = !taskId.isBlank()
        ? BackgroundTaskRegistry.get().kill(taskId)
        : BackgroundTaskRegistry.get().killByAppId(appId);
    if (!killed) return ToolResult.error("Task not found: " + resolveLabel(input));
    BackgroundTask task = !taskId.isBlank()
        ? BackgroundTaskRegistry.get().get(taskId)
        : BackgroundTaskRegistry.get().getByAppId(appId);
    return ToolResult.ok("Cancelled task " + resolveLabel(input)
        + (task == null ? "" : " (" + task.status() + ")"));
  }

  private static ToolResult restartTask(JsonNode input, ToolContext context) {
    String taskId = input != null ? input.path("task_id").asText("") : "";
    String appId = input != null ? input.path("app_id").asText("") : "";
    if (taskId.isBlank() && appId.isBlank()) return ToolResult.error("task_id or app_id is required");
    try {
      BackgroundTask task = !taskId.isBlank()
          ? BackgroundTaskRegistry.get().restart(taskId, (parts, cwd) -> validateRestart(parts, context))
          : BackgroundTaskRegistry.get().restartByAppId(appId, (parts, cwd) -> validateRestart(parts, context));
      if (task == null) return ToolResult.error("Task not found: " + resolveLabel(input));
      task = BackgroundTaskRegistry.get().awaitReady(task.id());
      return ToolResult.ok("Restarted task " + task.id()
          + (task.appId() == null || task.appId().isBlank() ? "" : " app=" + task.appId())
          + " pid=" + task.pid()
          + (task.healthStatus() == null || task.healthStatus().isBlank() ? "" : " health=" + task.healthStatus()));
    } catch (IllegalStateException error) {
      return ToolResult.error(error.getMessage());
    } catch (Exception error) {
      return ToolResult.error("Failed to restart " + resolveLabel(input) + ": " + error.getMessage());
    }
  }

  private static void appendTask(StringBuilder out, BackgroundTask task) {
    out.append(task.id())
        .append(task.appId() == null || task.appId().isBlank() ? "" : " app=" + task.appId())
        .append(" pid=").append(task.pid())
        .append(" status=").append(task.status())
        .append(" started=").append(task.startedAt())
        .append(" cwd=").append(task.workdir())
        .append(" command=").append(task.command());
    if ((task.healthUrl() != null && !task.healthUrl().isBlank()) || task.healthPort() > 0) {
      out.append(" health=");
      if (task.healthUrl() != null && !task.healthUrl().isBlank()) {
        out.append(task.healthUrl());
      } else {
        out.append("tcp://127.0.0.1:").append(task.healthPort());
      }
      if (task.healthStatus() != null && !task.healthStatus().isBlank()) {
        out.append("[").append(task.healthStatus()).append("]");
      }
    }
    out
        .append("\n");
  }

  private static BackgroundTask resolveTask(JsonNode input) {
    String taskId = input != null ? input.path("task_id").asText("") : "";
    String appId = input != null ? input.path("app_id").asText("") : "";
    if (!taskId.isBlank()) {
      return BackgroundTaskRegistry.get().get(taskId);
    }
    if (!appId.isBlank()) {
      return BackgroundTaskRegistry.get().getByAppId(appId);
    }
    return null;
  }

  private static String resolveLabel(JsonNode input) {
    String taskId = input != null ? input.path("task_id").asText("") : "";
    if (!taskId.isBlank()) return taskId;
    String appId = input != null ? input.path("app_id").asText("") : "";
    return appId;
  }

  private static void validateRestart(java.util.List<String> parts, ToolContext context) {
    if (parts == null || parts.isEmpty()) {
      throw new IllegalStateException("Stored command is empty");
    }
    String executable = parts.getFirst();
    java.util.List<String> args = parts.subList(1, parts.size());
    if (!context.permissions().canRun(executable, args)) {
      String reason = context.permissions().classifyDangerousCommand(executable, args);
      throw new IllegalStateException("Command requires approval: "
          + (reason == null ? executable : reason)
          + context.permissions().formatLastDenialFeedback());
    }
    String compatError = RunCommandTool.checkCommandAvailability(executable);
    if (compatError != null) {
      throw new IllegalStateException(compatError);
    }
  }

  private static String excerpt(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "...";
  }
}
