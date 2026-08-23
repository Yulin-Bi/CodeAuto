package com.codeauto.web;

import com.codeauto.core.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Extracts and safely previews workspace files touched by agent tool calls. */
final class WorkspaceFileService {
  private static final int MAX_PREVIEW_BYTES = 200_000;

  record FileEntry(String path, String operation, boolean exists, long size, String modifiedAt) {}

  private WorkspaceFileService() {}

  static List<FileEntry> collect(Path cwd, List<ChatMessage> messages, List<JsonNode> trace) {
    Path root = cwd.toAbsolutePath().normalize();
    Map<String, String> paths = new LinkedHashMap<>();
    int messageStart = 0;
    for (int index = 0; index < messages.size(); index++) {
      if (messages.get(index) instanceof ChatMessage.UserMessage) messageStart = index + 1;
    }
    for (ChatMessage message : messages.subList(messageStart, messages.size())) {
      if (message instanceof ChatMessage.AssistantToolCallMessage call) {
        collectInput(root, call.toolName(), call.input(), paths);
      } else if (message instanceof ChatMessage.AssistantRawMessage raw && raw.content() != null && raw.content().isArray()) {
        for (JsonNode block : raw.content()) {
          if ("tool_use".equals(block.path("type").asText())) {
            collectInput(root, block.path("name").asText("tool"), block.path("input"), paths);
          }
        }
      }
    }
    int traceStart = 0;
    for (int index = 0; index < trace.size(); index++) {
      if ("user_message".equals(trace.get(index).path("type").asText())) traceStart = index + 1;
    }
    for (JsonNode event : trace.subList(traceStart, trace.size())) {
      if ("tool_start".equals(event.path("type").asText())) {
        JsonNode payload = event.path("payload");
        collectInput(root, payload.path("name").asText("tool"), payload.path("input"), paths);
      }
    }
    List<FileEntry> result = new ArrayList<>();
    for (var item : paths.entrySet()) {
      Path file = root.resolve(item.getKey()).normalize();
      try {
        boolean exists = Files.isRegularFile(file);
        result.add(new FileEntry(item.getKey().replace('\\', '/'), item.getValue(), exists,
            exists ? Files.size(file) : 0L,
            exists ? Files.getLastModifiedTime(file).toInstant().toString() : ""));
      } catch (Exception ignored) {
        result.add(new FileEntry(item.getKey().replace('\\', '/'), item.getValue(), false, 0L, ""));
      }
    }
    return result;
  }

  static String readText(Path cwd, String relativePath) throws Exception {
    Path root = cwd.toAbsolutePath().normalize();
    Path file = root.resolve(relativePath == null ? "" : relativePath).normalize();
    if (!file.startsWith(root)) throw new IllegalArgumentException("file is outside the workspace");
    if (!Files.isRegularFile(file)) throw new IllegalArgumentException("file does not exist");
    long size = Files.size(file);
    if (size > MAX_PREVIEW_BYTES) throw new IllegalArgumentException("file is too large to preview");
    byte[] bytes = Files.readAllBytes(file);
    for (byte value : bytes) if (value == 0) throw new IllegalArgumentException("binary files cannot be previewed");
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void collectInput(Path root, String toolName, JsonNode input, Map<String, String> paths) {
    String name = toolName == null ? "tool" : toolName;
    String lower = name.toLowerCase(Locale.ROOT);
    if (!(lower.contains("write") || lower.contains("edit") || lower.contains("patch")
        || lower.contains("delete") || lower.contains("move") || lower.contains("rename"))) return;
    scan(root, name, input, null, paths);
  }

  private static void scan(Path root, String operation, JsonNode node, String field, Map<String, String> paths) {
    if (node == null || node.isMissingNode() || node.isNull()) return;
    if (node.isObject()) {
      node.fields().forEachRemaining(entry -> scan(root, operation, entry.getValue(), entry.getKey(), paths));
      return;
    }
    if (node.isArray()) {
      for (JsonNode child : node) scan(root, operation, child, field, paths);
      return;
    }
    if (!node.isTextual() || field == null || !isPathField(field)) return;
    String value = node.asText().trim();
    if (value.isBlank() || value.length() > 500 || value.contains("\n") || value.contains("\r")) return;
    try {
      Path candidate = Path.of(value);
      Path absolute = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).toAbsolutePath().normalize();
      if (absolute.startsWith(root) && !absolute.equals(root)) {
        paths.put(root.relativize(absolute).toString(), operation);
      }
    } catch (Exception ignored) {
      // A tool parameter named like a path may still contain a non-path value.
    }
  }

  private static boolean isPathField(String field) {
    String key = field.toLowerCase(Locale.ROOT).replace("_", "");
    return key.contains("path") || key.equals("file") || key.equals("filename")
        || key.equals("destination") || key.equals("source");
  }
}
