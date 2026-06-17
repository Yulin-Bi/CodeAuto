package com.codeauto.background;

import com.codeauto.config.RuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BackgroundTaskStore {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FILE_NAME = "managed-apps.json";

  List<StoredManagedApp> readManagedApps() {
    Path path = storePath();
    if (!Files.exists(path)) {
      return List.of();
    }
    try {
      JsonNode root = MAPPER.readTree(path.toFile());
      if (!(root instanceof ObjectNode object)) {
        return List.of();
      }
      List<StoredManagedApp> apps = new ArrayList<>();
      object.fields().forEachRemaining(field -> {
        JsonNode node = field.getValue();
        String appId = text(node, "appId", field.getKey());
        String taskId = text(node, "taskId", "");
        String command = text(node, "command", "");
        String workdir = text(node, "workdir", "");
        if (appId.isBlank() || taskId.isBlank() || command.isBlank() || workdir.isBlank()) {
          return;
        }
        List<String> commandParts = new ArrayList<>();
        JsonNode partsNode = node.path("commandParts");
        if (partsNode.isArray()) {
          partsNode.forEach(part -> commandParts.add(part.asText("")));
        }
        if (commandParts.isEmpty()) {
          commandParts.add(command);
        }
        apps.add(new StoredManagedApp(
            taskId,
            appId,
            command,
            List.copyOf(commandParts),
            workdir,
            node.path("pid").asLong(-1L),
            node.path("startedAt").asLong(System.currentTimeMillis()),
            text(node, "status", "running"),
            text(node, "healthUrl", ""),
            node.path("healthPort").asInt(0),
            node.path("startupTimeoutSeconds").asInt(0),
            text(node, "healthStatus", "")));
      });
      return apps;
    } catch (Exception ignored) {
      return List.of();
    }
  }

  void writeManagedApps(List<StoredManagedApp> apps) {
    try {
      ObjectNode root = MAPPER.createObjectNode();
      for (StoredManagedApp app : apps) {
        if (app.appId() == null || app.appId().isBlank()) {
          continue;
        }
        ObjectNode node = root.putObject(app.appId());
        node.put("taskId", app.taskId());
        node.put("appId", app.appId());
        node.put("command", app.command());
        ArrayNode parts = node.putArray("commandParts");
        for (String part : app.commandParts()) {
          parts.add(part);
        }
        node.put("workdir", app.workdir());
        node.put("pid", app.pid());
        node.put("startedAt", app.startedAt());
        node.put("status", app.status());
        if (app.healthUrl() != null && !app.healthUrl().isBlank()) {
          node.put("healthUrl", app.healthUrl());
        }
        if (app.healthPort() > 0) {
          node.put("healthPort", app.healthPort());
        }
        if (app.startupTimeoutSeconds() > 0) {
          node.put("startupTimeoutSeconds", app.startupTimeoutSeconds());
        }
        if (app.healthStatus() != null && !app.healthStatus().isBlank()) {
          node.put("healthStatus", app.healthStatus());
        }
      }
      Path path = storePath();
      Files.createDirectories(path.getParent());
      Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    } catch (Exception ignored) {
      // Persistence is best effort.
    }
  }

  static Path storeHome() {
    return RuntimeConfig.homeDir().toAbsolutePath().normalize();
  }

  private Path storePath() {
    return storeHome().resolve(FILE_NAME);
  }

  private static String text(JsonNode node, String field, String fallback) {
    String value = node.path(field).asText("");
    return value == null || value.isBlank() ? fallback : value;
  }

  record StoredManagedApp(
      String taskId,
      String appId,
      String command,
      List<String> commandParts,
      String workdir,
      long pid,
      long startedAt,
      String status,
      String healthUrl,
      int healthPort,
      int startupTimeoutSeconds,
      String healthStatus
  ) {
  }
}
