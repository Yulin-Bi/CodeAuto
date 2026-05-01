package com.codeauto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.mcp.McpBackedTool;
import com.codeauto.mcp.McpServerConfig;
import com.codeauto.mcp.McpToolSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpBackedToolTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void exposesStablePrefixedToolNameAndSchema() {
    var tool = new McpBackedTool(
        new McpServerConfig("my server", "node", List.of("server.js")),
        new McpToolSummary("my server", "search-files", "Search files", MAPPER.createObjectNode().put("type", "object")));

    assertEquals("mcp_my_server_search_files", tool.name());
    assertTrue(tool.description().contains("Search files"));
    assertEquals("object", tool.inputSchema().path("type").asText());
  }
}
