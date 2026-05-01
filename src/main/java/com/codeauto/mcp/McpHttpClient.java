package com.codeauto.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Streamable HTTP MCP client.
 * Implements the MCP Streamable HTTP transport (2025 draft):
 * - POST JSON-RPC requests to a server URL
 * - Responses are either single JSON-RPC responses or SSE streams
 * - Supports optional session via List&lt;String&gt; (not used in stateless mode)
 */
public class McpHttpClient implements AutoCloseable {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  private final McpServerConfig config;
  private final int requestTimeoutSec;
  private int nextId = 1;

  public McpHttpClient(McpServerConfig config) {
    this(config, 30);
  }

  public McpHttpClient(McpServerConfig config, int requestTimeoutSec) {
    if (!config.isHttp()) {
      throw new IllegalArgumentException("McpHttpClient requires an HTTP server config with a URL");
    }
    this.config = config;
    this.requestTimeoutSec = requestTimeoutSec;
  }

  public List<McpToolSummary> listTools() throws Exception {
    initialize();
    JsonNode result = request("tools/list", MAPPER.createObjectNode());
    List<McpToolSummary> tools = new ArrayList<>();
    for (JsonNode tool : result.path("tools")) {
      tools.add(new McpToolSummary(
          config.name(),
          tool.path("name").asText(),
          tool.path("description").asText(""),
          tool.path("inputSchema")));
    }
    return tools;
  }

  public JsonNode callTool(String toolName, JsonNode arguments) throws Exception {
    initialize();
    ObjectNode params = MAPPER.createObjectNode();
    params.put("name", toolName);
    params.set("arguments", arguments == null ? MAPPER.createObjectNode() : arguments);
    return request("tools/call", params);
  }

  public JsonNode listResources() throws Exception {
    initialize();
    return request("resources/list", MAPPER.createObjectNode());
  }

  public JsonNode readResource(String uri) throws Exception {
    initialize();
    ObjectNode params = MAPPER.createObjectNode();
    params.put("uri", uri);
    return request("resources/read", params);
  }

  public JsonNode listPrompts() throws Exception {
    initialize();
    return request("prompts/list", MAPPER.createObjectNode());
  }

  public JsonNode getPrompt(String name, JsonNode arguments) throws Exception {
    initialize();
    ObjectNode params = MAPPER.createObjectNode();
    params.put("name", name);
    if (arguments != null && !arguments.isMissingNode() && !arguments.isNull()) {
      params.set("arguments", arguments);
    }
    return request("prompts/get", params);
  }

  private void initialize() throws Exception {
    // Initialize is a no-op for HTTP transport:
    // we skip the stdio-style initialize/notifications/initialized handshake
    // because the Streamable HTTP spec sends initialize as the first request.
  }

  public JsonNode request(String method, JsonNode params) throws Exception {
    int id = nextId++;
    ObjectNode body = MAPPER.createObjectNode();
    body.put("jsonrpc", "2.0");
    body.put("id", id);
    body.put("method", method);
    body.set("params", params);

    HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
        .uri(URI.create(config.url()))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .timeout(Duration.ofSeconds(requestTimeoutSec))
        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));

    // Apply configured headers
    for (Map.Entry<String, String> entry : config.env().entrySet()) {
      reqBuilder.header(entry.getKey(), entry.getValue());
    }

    HttpRequest request = reqBuilder.build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    int status = response.statusCode();
    String responseBody = response.body();

    if (status < 200 || status >= 300) {
      throw new IllegalStateException("MCP HTTP " + config.name() + " returned status " + status
          + ": " + truncate(responseBody, 200));
    }

    if (responseBody == null || responseBody.isBlank()) {
      throw new IllegalStateException("MCP HTTP " + config.name() + " returned empty response");
    }

    // Try to parse as JSON-RPC response
    try {
      JsonNode json = MAPPER.readTree(responseBody);
      if (json.has("error")) {
        throw new IllegalStateException("MCP HTTP " + config.name() + " error: " + json.get("error"));
      }
      return json.path("result");
    } catch (Exception e) {
      if (e instanceof IllegalStateException) throw (IllegalStateException) e;
      // Fallback: try to handle text/event-stream (SSE)
      throw new IllegalStateException("MCP HTTP " + config.name()
          + ": expected JSON-RPC response but got content type: " + response.headers().firstValue("content-type").orElse("unknown"));
    }
  }

  @Override
  public void close() throws Exception {
    // Nothing to close for HTTP client - connections are managed by the shared HttpClient
  }

  private static String truncate(String value, int max) {
    return value == null ? "" : value.length() <= max ? value : value.substring(0, max) + "...";
  }
}
