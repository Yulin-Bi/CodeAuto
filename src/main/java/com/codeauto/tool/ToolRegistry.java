package com.codeauto.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {
  private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

  public ToolRegistry(List<ToolDefinition> definitions) {
    addTools(definitions);
  }

  public void addTools(List<ToolDefinition> definitions) {
    for (ToolDefinition definition : definitions) {
      tools.putIfAbsent(definition.name(), definition);
    }
  }

  public List<ToolDefinition> list() {
    return Collections.unmodifiableList(new ArrayList<>(tools.values()));
  }

  public ToolDefinition find(String name) {
    return tools.get(name);
  }

  public ToolResult execute(String toolName, com.fasterxml.jackson.databind.JsonNode input, ToolContext context) {
    ToolDefinition tool = find(toolName);
    if (tool == null) {
      return ToolResult.error("Unknown tool: " + toolName);
    }
    try {
      return tool.run(input, context);
    } catch (Exception error) {
      return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
    }
  }
}
