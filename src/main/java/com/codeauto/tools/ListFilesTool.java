package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class ListFilesTool implements ToolDefinition {
  @Override public String name() { return "list_files"; }
  @Override public String description() { return "List files in a directory."; }
  @Override
  public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    props.set("path", JsonSchemas.stringProp("Directory path (default: workspace root)"));
    return schema;
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    Path dir = context.cwd().resolve(JsonSchemas.text(input, "path", ".")).normalize();
    if (!context.permissions().canRead(dir)) return ToolResult.error("Path is not allowed: " + dir);
    if (!Files.isDirectory(dir)) return ToolResult.error("Not a directory: " + dir);
    try (var stream = Files.list(dir)) {
      return ToolResult.ok(stream
          .map(path -> Files.isDirectory(path) ? path.getFileName() + "/" : path.getFileName().toString())
          .sorted()
          .collect(Collectors.joining("\n")));
    }
  }
}
