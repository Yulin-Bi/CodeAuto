package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GrepFilesTool implements ToolDefinition {
  @Override public String name() { return "grep_files"; }
  @Override public String description() { return "Search text files by substring."; }
  @Override public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    props.set("pattern", JsonSchemas.stringProp("Search substring"));
    props.set("path", JsonSchemas.stringProp("Directory to search in (default: workspace root)"));
    return JsonSchemas.required(schema, "pattern");
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String pattern = JsonSchemas.text(input, "pattern", "");
    if (pattern.isBlank()) return ToolResult.error("pattern is required");
    Path root = context.cwd().resolve(JsonSchemas.text(input, "path", ".")).normalize();
    if (!context.permissions().canRead(root)) return ToolResult.error("Path is not allowed: " + root);
    List<String> matches = new ArrayList<>();
    try (var paths = Files.walk(root)) {
      for (Path file : paths.filter(Files::isRegularFile).limit(500).toList()) {
        try {
          int lineNo = 0;
          for (String line : Files.readAllLines(file)) {
            lineNo++;
            if (line.contains(pattern)) {
              matches.add(root.relativize(file) + ":" + lineNo + ": " + line);
            }
          }
        } catch (Exception ignored) {
          // Binary or unreadable files are skipped.
        }
      }
    }
    return ToolResult.ok(String.join("\n", matches));
  }
}
