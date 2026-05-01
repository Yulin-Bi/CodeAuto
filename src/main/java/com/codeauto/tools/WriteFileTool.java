package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;

public class WriteFileTool implements ToolDefinition {
  @Override public String name() { return "write_file"; }
  @Override public String description() { return "Write a UTF-8 text file inside the workspace."; }
  @Override public JsonNode inputSchema() { return JsonSchemas.objectSchema(); }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String rawPath = JsonSchemas.text(input, "path", "");
    String content = JsonSchemas.text(input, "content", "");
    if (rawPath.isBlank()) return ToolResult.error("path is required");
    Path file = context.cwd().resolve(rawPath).normalize();
    String before = Files.exists(file) ? Files.readString(file) : "";
    return FileReviewService.reviewAndWrite(file, before, content, context, "Wrote");
  }
}
