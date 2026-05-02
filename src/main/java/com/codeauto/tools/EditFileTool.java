package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;

public class EditFileTool implements ToolDefinition {
  @Override public String name() { return "edit_file"; }
  @Override public String description() { return "Replace text in a UTF-8 file."; }
  @Override public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    props.set("path", JsonSchemas.stringProp("File path to edit"));
    props.set("oldText", JsonSchemas.stringProp("Text to search for (use old_text as alternative)"));
    props.set("newText", JsonSchemas.stringProp("Replacement text (use new_text as alternative)"));
    props.set("replaceAll", JsonSchemas.booleanProp("Replace all occurrences (use replace_all as alternative)"));
    return JsonSchemas.required(schema, "path", "oldText");
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String rawPath = JsonSchemas.textAny(input, "", "path", "file_path", "filepath", "filePath");
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
