package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModifyFileTool implements ToolDefinition {
  @Override public String name() { return "modify_file"; }
  @Override public String description() { return "Replace a file with complete UTF-8 content."; }
  @Override public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    props.set("path", JsonSchemas.stringProp("File path to modify"));
    props.set("file_path", JsonSchemas.stringProp("Alternative path field"));
    props.set("content", JsonSchemas.stringProp("Complete file content to replace with"));
    return JsonSchemas.required(schema, "path", "content");
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String rawPath = JsonSchemas.textAny(input, "", "path", "file_path", "filepath", "filePath");
    String content = JsonSchemas.text(input, "content", "");
    if (rawPath.isBlank()) return ToolResult.error("path is required");
    if (content.isEmpty()) return ToolResult.error("content is required");
    Path file = context.cwd().resolve(rawPath).normalize();
    String before = Files.exists(file) ? Files.readString(file) : "";
    return FileReviewService.reviewAndWrite(file, before, content, context, "Modified");
  }
}
