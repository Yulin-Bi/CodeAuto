package com.codeauto.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolSummary(String serverName, String name, String description, JsonNode inputSchema) {
  public McpToolSummary(String serverName, String name, String description) {
    this(serverName, name, description, null);
  }
}
