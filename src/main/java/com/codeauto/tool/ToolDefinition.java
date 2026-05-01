package com.codeauto.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface ToolDefinition {
  String name();

  String description();

  JsonNode inputSchema();

  ToolResult run(JsonNode input, ToolContext context) throws Exception;
}
