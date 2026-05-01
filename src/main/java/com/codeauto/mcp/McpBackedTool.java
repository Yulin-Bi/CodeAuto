package com.codeauto.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;

public class McpBackedTool implements ToolDefinition {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final McpServerConfig server;
  private final McpToolSummary tool;

  public McpBackedTool(McpServerConfig server, McpToolSummary tool) {
    this.server = server;
    this.tool = tool;
  }

  @Override
  public String name() {
    return "mcp_" + sanitize(server.name()) + "_" + sanitize(tool.name());
  }

  @Override
  public String description() {
    return "MCP " + server.name() + "/" + tool.name() + ": " + tool.description();
  }

  @Override
  public JsonNode inputSchema() {
    return tool.inputSchema() == null || tool.inputSchema().isMissingNode()
        ? MAPPER.createObjectNode().put("type", "object")
        : tool.inputSchema();
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    JsonNode result = McpService.withProtocolFallback(server, client -> client.callTool(tool.name(), input));
    return ToolResult.ok(formatResult(result));
  }

  private static String formatResult(JsonNode result) {
    JsonNode content = result.path("content");
    if (content.isArray()) {
      StringBuilder out = new StringBuilder();
      for (JsonNode block : content) {
        if ("text".equals(block.path("type").asText())) {
          out.append(block.path("text").asText()).append("\n");
        } else {
          out.append(block).append("\n");
        }
      }
      return out.toString().trim();
    }
    return result.toString();
  }

  private static String sanitize(String value) {
    return value.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
  }
}
