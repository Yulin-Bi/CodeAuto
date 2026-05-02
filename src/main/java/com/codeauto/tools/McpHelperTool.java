package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.manage.ManagementStore;
import com.codeauto.mcp.McpClient;
import com.codeauto.mcp.McpHttpClient;
import com.codeauto.mcp.McpServerConfig;
import com.codeauto.mcp.McpService;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;

public class McpHelperTool implements ToolDefinition {
  public enum Kind {
    LIST_RESOURCES,
    READ_RESOURCE,
    LIST_PROMPTS,
    GET_PROMPT
  }

  private final Kind kind;

  public McpHelperTool(Kind kind) {
    this.kind = kind;
  }

  @Override
  public String name() {
    return switch (kind) {
      case LIST_RESOURCES -> "list_mcp_resources";
      case READ_RESOURCE -> "read_mcp_resource";
      case LIST_PROMPTS -> "list_mcp_prompts";
      case GET_PROMPT -> "get_mcp_prompt";
    };
  }

  @Override
  public String description() {
    return switch (kind) {
      case LIST_RESOURCES -> "List optional MCP resources exposed by connected MCP servers.";
      case READ_RESOURCE -> "Read a specific optional MCP resource by server and URI.";
      case LIST_PROMPTS -> "List optional MCP prompts exposed by connected MCP servers.";
      case GET_PROMPT -> "Fetch a rendered optional MCP prompt by server, prompt name, and optional arguments.";
    };
  }

  @Override
  public JsonNode inputSchema() {
    return switch (kind) {
      case LIST_RESOURCES -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("server", JsonSchemas.stringProp("Server name (omit for all)"));
        yield schema;
      }
      case READ_RESOURCE -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("server", JsonSchemas.stringProp("MCP server name"));
        props.set("uri", JsonSchemas.stringProp("Resource URI to read"));
        yield JsonSchemas.required(schema, "server", "uri");
      }
      case LIST_PROMPTS -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("server", JsonSchemas.stringProp("Server name (omit for all)"));
        yield schema;
      }
      case GET_PROMPT -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("server", JsonSchemas.stringProp("MCP server name"));
        props.set("name", JsonSchemas.stringProp("Prompt name"));
        props.set("arguments", JsonSchemas.MAPPER.createObjectNode()
            .put("type", "object").put("description", "Optional prompt arguments"));
        yield JsonSchemas.required(schema, "server", "name");
      }
    };
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    McpService service = new McpService(new ManagementStore(), context.cwd());
    return switch (kind) {
      case LIST_RESOURCES -> listResources(input, service);
      case READ_RESOURCE -> readResource(input, service);
      case LIST_PROMPTS -> listPrompts(input, service);
      case GET_PROMPT -> getPrompt(input, service);
    };
  }

  private static ToolResult listResources(JsonNode input, McpService service) {
    String target = JsonSchemas.text(input, "server", "");
    List<String> lines = new ArrayList<>();
    for (McpServerConfig server : targetServers(service, target)) {
      try {
        JsonNode result = McpService.runOnServer(server, new McpService.McpClientOperation<JsonNode>() {
          @Override public JsonNode runStdio(McpClient client) throws Exception { return client.listResources(); }
          @Override public JsonNode runHttp(McpHttpClient client) throws Exception { return client.listResources(); }
        });
        for (JsonNode resource : result.path("resources")) {
          String uri = resource.path("uri").asText("");
          if (uri.isBlank()) continue;
          String name = resource.path("name").asText("");
          String description = resource.path("description").asText("");
          lines.add(server.name() + ": " + uri
              + (name.isBlank() ? "" : " (" + name + ")")
              + (description.isBlank() ? "" : " - " + description));
        }
      } catch (Exception error) {
        lines.add(server.name() + ": failed to list resources (" + error.getMessage() + ")");
      }
    }
    return ToolResult.ok(lines.isEmpty()
        ? "Connected MCP servers did not publish any MCP resources. This does not mean MCP tools are unavailable."
        : String.join("\n", lines));
  }

  private static ToolResult readResource(JsonNode input, McpService service) throws Exception {
    String serverName = JsonSchemas.text(input, "server", "");
    String uri = JsonSchemas.text(input, "uri", "");
    if (serverName.isBlank()) return ToolResult.error("server is required");
    if (uri.isBlank()) return ToolResult.error("uri is required");
    McpServerConfig server = findServer(service, serverName);
    if (server == null) return ToolResult.error("Unknown MCP server: " + serverName);
    JsonNode result = McpService.runOnServer(server, new McpService.McpClientOperation<JsonNode>() {
      @Override public JsonNode runStdio(McpClient client) throws Exception { return client.readResource(uri); }
      @Override public JsonNode runHttp(McpHttpClient client) throws Exception { return client.readResource(uri); }
    });
    return ToolResult.ok(formatContentResult(result));
  }

  private static ToolResult listPrompts(JsonNode input, McpService service) {
    String target = JsonSchemas.text(input, "server", "");
    List<String> lines = new ArrayList<>();
    for (McpServerConfig server : targetServers(service, target)) {
      try {
        JsonNode result = McpService.runOnServer(server, new McpService.McpClientOperation<JsonNode>() {
          @Override public JsonNode runStdio(McpClient client) throws Exception { return client.listPrompts(); }
          @Override public JsonNode runHttp(McpHttpClient client) throws Exception { return client.listPrompts(); }
        });
        for (JsonNode prompt : result.path("prompts")) {
          String name = prompt.path("name").asText("");
          if (name.isBlank()) continue;
          String description = prompt.path("description").asText("");
          String args = formatPromptArguments(prompt.path("arguments"));
          lines.add(server.name() + ": " + name
              + (args.isBlank() ? "" : " args=[" + args + "]")
              + (description.isBlank() ? "" : " - " + description));
        }
      } catch (Exception error) {
        lines.add(server.name() + ": failed to list prompts (" + error.getMessage() + ")");
      }
    }
    return ToolResult.ok(lines.isEmpty()
        ? "Connected MCP servers did not publish any MCP prompts. This does not mean MCP tools are unavailable."
        : String.join("\n", lines));
  }

  private static ToolResult getPrompt(JsonNode input, McpService service) throws Exception {
    String serverName = JsonSchemas.text(input, "server", "");
    String name = JsonSchemas.text(input, "name", "");
    if (serverName.isBlank()) return ToolResult.error("server is required");
    if (name.isBlank()) return ToolResult.error("name is required");
    McpServerConfig server = findServer(service, serverName);
    if (server == null) return ToolResult.error("Unknown MCP server: " + serverName);
    JsonNode arguments = input == null ? null : input.get("arguments");
    JsonNode result = McpService.runOnServer(server, new McpService.McpClientOperation<JsonNode>() {
      @Override public JsonNode runStdio(McpClient client) throws Exception { return client.getPrompt(name, arguments); }
      @Override public JsonNode runHttp(McpHttpClient client) throws Exception { return client.getPrompt(name, arguments); }
    });
    return ToolResult.ok(formatContentResult(result));
  }

  private static List<McpServerConfig> targetServers(McpService service, String target) {
    List<McpServerConfig> servers = service.configuredServers();
    if (target == null || target.isBlank()) {
      return servers;
    }
    return servers.stream().filter(server -> server.name().equals(target)).toList();
  }

  private static McpServerConfig findServer(McpService service, String name) {
    return service.configuredServers().stream()
        .filter(server -> server.name().equals(name))
        .findFirst()
        .orElse(null);
  }

  private static String formatPromptArguments(JsonNode arguments) {
    if (!arguments.isArray()) return "";
    List<String> values = new ArrayList<>();
    for (JsonNode argument : arguments) {
      String name = argument.path("name").asText("");
      if (name.isBlank()) continue;
      values.add(name + (argument.path("required").asBoolean(false) ? "*" : ""));
    }
    return String.join(", ", values);
  }

  private static String formatContentResult(JsonNode result) {
    JsonNode content = result.path("contents").isArray() ? result.path("contents") : result.path("messages");
    if (!content.isArray()) {
      content = result.path("content");
    }
    if (!content.isArray()) {
      return result.toString();
    }
    List<String> lines = new ArrayList<>();
    for (JsonNode block : content) {
      if (block.has("text")) {
        lines.add(block.path("text").asText());
      } else if (block.has("content")) {
        lines.add(formatContentResult(block));
      } else {
        lines.add(block.toString());
      }
    }
    return String.join("\n", lines).trim();
  }
}
