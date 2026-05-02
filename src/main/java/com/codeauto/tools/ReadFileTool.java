package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;

public class ReadFileTool implements ToolDefinition {
  @Override public String name() { return "read_file"; }
  @Override public String description() { return "Read a UTF-8 text file."; }
  @Override public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    props.set("path", JsonSchemas.stringProp("File path to read"));
    return JsonSchemas.required(schema, "path");
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String rawPath = JsonSchemas.text(input, "path", JsonSchemas.text(input, "file_path", ""));
    if (rawPath.isBlank()) return ToolResult.error("path is required");
    Path file = context.cwd().resolve(rawPath).normalize();
    if (!context.permissions().canRead(file)) return ToolResult.error("Path is not allowed: " + file);
    return ToolResult.ok(Files.readString(file));
  }
}
