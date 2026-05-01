package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;

public class EditFileTool implements ToolDefinition {
  @Override public String name() { return "edit_file"; }
  @Override public String description() { return "Replace text in a UTF-8 file."; }
  @Override public JsonNode inputSchema() { return JsonSchemas.objectSchema(); }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String rawPath = JsonSchemas.text(input, "path", "");
    String oldText = JsonSchemas.text(input, "oldText", JsonSchemas.text(input, "old_text", ""));
    String newText = JsonSchemas.text(input, "newText", JsonSchemas.text(input, "new_text", ""));
    boolean replaceAll = input != null && input.path("replaceAll").asBoolean(input.path("replace_all").asBoolean(false));
    if (rawPath.isBlank()) return ToolResult.error("path is required");
    if (oldText.isEmpty()) return ToolResult.error("oldText is required");
    Path file = context.cwd().resolve(rawPath).normalize();
    String before = Files.readString(file);
    if (!before.contains(oldText)) return ToolResult.error("oldText was not found in " + file);
    String after = replaceAll
        ? before.replace(oldText, newText)
        : before.replaceFirst(java.util.regex.Pattern.quote(oldText), java.util.regex.Matcher.quoteReplacement(newText));
    return FileReviewService.reviewAndWrite(file, before, after, context, "Edited");
  }
}
