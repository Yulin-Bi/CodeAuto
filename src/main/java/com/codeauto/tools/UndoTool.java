package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import com.codeauto.undo.UndoRecord;
import com.codeauto.undo.UndoStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class UndoTool implements ToolDefinition {

  private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.systemDefault());

  private final Kind kind;

  public UndoTool(Kind kind) {
    this.kind = kind;
  }

  @Override
  public String name() {
    return switch (kind) {
      case UNDO -> "undo";
      case UNDO_LIST -> "undo_list";
      case UNDO_ALL -> "undo_all";
    };
  }

  @Override
  public String description() {
    return switch (kind) {
      case UNDO -> "Undo the last file operation (write, edit, modify, patch) by restoring previous file content. "
          + "Use optional id parameter to undo a specific operation.";
      case UNDO_LIST -> "List all undo records for file operations in this session.";
      case UNDO_ALL -> "Undo all file operations in reverse order.";
    };
  }

  @Override
  public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    if (kind == Kind.UNDO) {
      props.set("id", JsonSchemas.stringProp("Undo record id (omit to undo the latest operation)"));
    }
    return schema;
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    UndoStore store = new UndoStore(context.cwd());
    return switch (kind) {
      case UNDO -> runUndo(input, store, context);
      case UNDO_LIST -> runList(store);
      case UNDO_ALL -> runUndoAll(store, context);
    };
  }

  private ToolResult runUndo(JsonNode input, UndoStore store, ToolContext context) throws Exception {
    String id = JsonSchemas.text(input, "id", "");
    UndoRecord record;
    if (!id.isBlank()) {
      record = store.load(id);
      if (record == null) {
        return ToolResult.error("Undo record not found: " + id);
      }
      if (record.undone()) {
        return ToolResult.error("Undo record " + id + " has already been undone.");
      }
    } else {
      record = store.getLatest();
      if (record == null) {
        return ToolResult.ok("No operations to undo.");
      }
    }

    Path file = store.resolveFilePath(record.filePath());
    String currentContent;
    boolean fileExists = Files.exists(file);

    if (!fileExists && record.beforeContent().isEmpty()) {
      // File was created by the original operation and is already gone.
      store.markUndone(record.id());
      return ToolResult.ok("Undid " + record.id() + " [" + record.toolName() + "]: file " + record.filePath()
          + " was already deleted.");
    }

    currentContent = fileExists ? Files.readString(file) : "";

    // Apply undo: restore beforeContent
    if (record.beforeContent().isEmpty()) {
      // Original operation created this file — undo means delete it
      Files.deleteIfExists(file);
    } else {
      Files.createDirectories(file.getParent());
      Files.writeString(file, record.beforeContent());
    }

    // Create redo record (current content becomes the new "before" for redo)
    try {
      store.save(null, "undo", file, currentContent);
    } catch (Exception e) {
      System.err.println("[CodeAuto] Failed to save redo record: " + e.getMessage());
    }

    store.markUndone(record.id());

    String diff = generateUndoDiff(record.filePath(), currentContent, record.beforeContent());
    return ToolResult.ok("Undid " + record.id() + " [" + record.toolName() + "]: restored " + record.filePath()
        + "\n" + diff);
  }

  private ToolResult runList(UndoStore store) throws Exception {
    List<UndoRecord> records = store.list(true);
    if (records.isEmpty()) {
      return ToolResult.ok("No undo records.");
    }
    StringBuilder sb = new StringBuilder();
    sb.append(records.size()).append(" undo record(s):\n");
    sb.append(String.format("%-10s %-10s %-8s %-20s %s\n", "ID", "TOOL", "STATUS", "TIME", "FILE"));
    for (UndoRecord r : records) {
      String status = r.undone() ? "undone" : "active";
      String time = TIME_FMT.format(r.timestamp());
      sb.append(String.format("%-10s %-10s %-8s %-20s %s\n",
          r.id(), r.toolName(), status, time, r.filePath()));
    }
    return ToolResult.ok(sb.toString().trim());
  }

  private ToolResult runUndoAll(UndoStore store, ToolContext context) throws Exception {
    List<UndoRecord> active = store.list(false);
    if (active.isEmpty()) {
      return ToolResult.ok("No operations to undo.");
    }

    // Reverse chronological order (newest first)
    java.util.Collections.reverse(active);

    StringBuilder result = new StringBuilder();
    result.append("Undid ").append(active.size()).append(" operation(s):\n");
    int undone = 0;

    for (UndoRecord record : active) {
      try {
        Path file = store.resolveFilePath(record.filePath());
        boolean fileExists = Files.exists(file);
        String currentContent = fileExists ? Files.readString(file) : "";

        if (record.beforeContent().isEmpty()) {
          Files.deleteIfExists(file);
        } else {
          Files.createDirectories(file.getParent());
          Files.writeString(file, record.beforeContent());
        }

        // Create redo record
        try {
          store.save(null, "undo", file, currentContent);
        } catch (Exception ignored) {
          // Best-effort.
        }

        store.markUndone(record.id());
        result.append("- ").append(record.id()).append(" [").append(record.toolName()).append("] ")
            .append(record.filePath()).append("\n");
        undone++;
      } catch (Exception e) {
        result.append("- ").append(record.id()).append(" [").append(record.toolName()).append("] FAILED: ")
            .append(e.getMessage()).append("\n");
      }
    }

    result.append("\nUndo complete: ").append(undone).append(" reversed.");
    return ToolResult.ok(result.toString());
  }

  private String generateUndoDiff(String filePath, String before, String after) {
    try {
      return FileReviewService.unifiedDiff(Path.of(filePath), before, after);
    } catch (Exception e) {
      return "(diff unavailable: " + e.getMessage() + ")";
    }
  }

  public enum Kind { UNDO, UNDO_LIST, UNDO_ALL }
}
