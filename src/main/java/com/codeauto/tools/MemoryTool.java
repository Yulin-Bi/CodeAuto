package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.config.RuntimeConfig;
import com.codeauto.memory.MemoryManager;
import com.codeauto.memory.MemoryType;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MemoryTool implements ToolDefinition {
  private final Kind kind;

  public MemoryTool(Kind kind) {
    this.kind = kind;
  }

  @Override
  public String name() {
    return switch (kind) {
      case SAVE -> "save_memory";
      case LIST -> "list_memory";
      case DELETE -> "delete_memory";
    };
  }

  @Override
  public String description() {
    return switch (kind) {
      case SAVE -> "Save a persistent memory. Before saving, call list_memory to check for contradictory or outdated memories on the same topic, and delete_memory them first. Then ask the user which destination they prefer: store, project, global, or codeauto.";
      case LIST -> "List persistent memories relevant to the workspace.";
      case DELETE -> "Delete a persistent memory by id.";
    };
  }

  @Override
  public JsonNode inputSchema() {
    return switch (kind) {
      case SAVE -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("title", JsonSchemas.stringProp("Memory title"));
        props.set("content", JsonSchemas.stringProp("Memory content (Markdown)"));
        props.set("type", JsonSchemas.stringProp("Memory type: user/feedback/project/reference (default: project)"));
        props.set("destination", JsonSchemas.stringProp(
            "Where to save: store (default), project, global, or codeauto. Ask the user before choosing."));
        props.set("tags", JsonSchemas.arrayProp("string", "Tags for this memory"));
        yield JsonSchemas.required(schema, "title", "content", "destination");
      }
      case LIST -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("query", JsonSchemas.stringProp("Search query to filter memories"));
        props.set("limit", JsonSchemas.integerProp("Max results (default: 10)"));
        yield schema;
      }
      case DELETE -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("id", JsonSchemas.stringProp("Memory id to delete"));
        yield JsonSchemas.required(schema, "id");
      }
    };
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) {
    MemoryManager manager = new MemoryManager();
    return switch (kind) {
      case SAVE -> save(input, context, manager);
      case LIST -> list(input, context, manager);
      case DELETE -> delete(input, manager);
    };
  }

  private static ToolResult save(JsonNode input, ToolContext context, MemoryManager manager) {
    String title = JsonSchemas.text(input, "title", "");
    String content = JsonSchemas.text(input, "content", "");
    if (title.isBlank()) return ToolResult.error("title is required");
    if (content.isBlank()) return ToolResult.error("content is required");
    MemoryType type = MemoryType.from(JsonSchemas.text(input, "type", "project"));
    String destination = JsonSchemas.text(input, "destination", "").toLowerCase();
    if (destination.isBlank()) {
      return ToolResult.error("destination is required: store, project, global, or codeauto");
    }
    try {
      return switch (destination) {
        case "store", "memory" -> {
          var entry = manager.save(type, title, context.cwd(), tags(input), content);
          yield ToolResult.ok("Saved memory " + entry.id() + " at " + entry.path());
        }
        case "project", "claude_project" -> {
          Path path = context.cwd().resolve("CLAUDE.md");
          appendClaudeMemory(path, content);
          yield ToolResult.ok("Saved memory instruction to " + path);
        }
        case "global", "user", "claude_global" -> {
          Path path = Path.of(System.getProperty("user.home"), ".claude", "CLAUDE.md");
          appendClaudeMemory(path, content);
          yield ToolResult.ok("Saved memory instruction to " + path);
        }
        case "codeauto", "app", "claude_codeauto" -> {
          Path path = RuntimeConfig.homeDir().resolve("CLAUDE.md");
          appendClaudeMemory(path, content);
          yield ToolResult.ok("Saved memory instruction to " + path);
        }
        default -> ToolResult.error("destination must be one of: store, project, global, codeauto");
      };
    } catch (Exception error) {
      return ToolResult.error("Failed to save memory: " + error.getMessage());
    }
  }

  private static void appendClaudeMemory(Path path, String content) throws Exception {
    Files.createDirectories(path.getParent());
    String existing = Files.isRegularFile(path) ? Files.readString(path) : "";
    String normalized = content.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    String existingNorm = existing.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    if (!normalized.isBlank() && !existingNorm.isBlank()
        && (existingNorm.equals(normalized) || existingNorm.contains(normalized) || normalized.contains(existingNorm))) {
      return;
    }
    StringBuilder entry = new StringBuilder();
    if (!existing.isBlank() && !existing.endsWith("\n")) entry.append("\n");
    entry.append("\n## CodeAuto Memory\n\n");
    entry.append("- ").append(content).append("\n");
    Files.writeString(path, entry.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private static ToolResult list(JsonNode input, ToolContext context, MemoryManager manager) {
    String query = JsonSchemas.text(input, "query", "");
    int limit = input == null ? 10 : Math.max(1, input.path("limit").asInt(10));
    var entries = query.isBlank()
        ? manager.relevant(context.cwd(), "", limit)
        : manager.relevant(context.cwd(), query, limit);
    if (entries.isEmpty()) return ToolResult.ok("(none)");
    StringBuilder out = new StringBuilder();
    for (var entry : entries) {
      out.append(entry.id())
          .append(" [").append(entry.type().name().toLowerCase()).append("] ")
          .append(entry.title())
          .append(" updated=").append(entry.updatedAt())
          .append("\n");
    }
    return ToolResult.ok(out.toString().trim());
  }

  private static ToolResult delete(JsonNode input, MemoryManager manager) {
    String id = JsonSchemas.text(input, "id", "");
    if (id.isBlank()) return ToolResult.error("id is required");
    return manager.delete(id)
        ? ToolResult.ok("Deleted memory " + id)
        : ToolResult.error("Memory not found: " + id);
  }

  private static List<String> tags(JsonNode input) {
    if (input == null) return List.of();
    JsonNode raw = input.get("tags");
    if (raw == null || raw.isNull()) return List.of();
    List<String> tags = new ArrayList<>();
    if (raw.isArray()) {
      for (JsonNode tag : raw) {
        if (!tag.asText("").isBlank()) tags.add(tag.asText().trim());
      }
    } else {
      for (String tag : raw.asText("").split(",")) {
        if (!tag.trim().isBlank()) tags.add(tag.trim());
      }
    }
    return tags;
  }

  public enum Kind {
    SAVE,
    LIST,
    DELETE
  }
}
