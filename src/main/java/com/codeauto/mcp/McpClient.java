package com.codeauto.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class McpClient implements AutoCloseable {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final McpServerConfig config;
  private Process process;
  private InputStream reader;
  private OutputStream writer;
  private int nextId = 1;

  public McpClient(McpServerConfig config) {
    this.config = config;
  }

  McpClient(McpServerConfig config, InputStream reader, OutputStream writer) {
    this.config = config;
    this.reader = reader;
    this.writer = writer;
  }

  public List<McpToolSummary> listTools() throws Exception {
    start();
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
    start();
    initialize();
    ObjectNode params = MAPPER.createObjectNode();
    params.put("name", toolName);
    params.set("arguments", arguments == null ? MAPPER.createObjectNode() : arguments);
    return request("tools/call", params);
  }

  public JsonNode listResources() throws Exception {
    start();
    initialize();
    return request("resources/list", MAPPER.createObjectNode());
  }

  public JsonNode readResource(String uri) throws Exception {
    start();
    initialize();
    ObjectNode params = MAPPER.createObjectNode();
    params.put("uri", uri);
    return request("resources/read", params);
  }

  public JsonNode listPrompts() throws Exception {
    start();
    initialize();
    return request("prompts/list", MAPPER.createObjectNode());
  }

  public JsonNode getPrompt(String name, JsonNode arguments) throws Exception {
    start();
    initialize();
    ObjectNode params = MAPPER.createObjectNode();
    params.put("name", name);
    if (arguments != null && !arguments.isMissingNode() && !arguments.isNull()) {
      params.set("arguments", arguments);
    }
    return request("prompts/get", params);
  }

  private void initialize() throws Exception {
    request("initialize", MAPPER.createObjectNode()
        .put("protocolVersion", "2024-11-05")
        .set("clientInfo", MAPPER.createObjectNode().put("name", "codeauto").put("version", "0.1.0")));
    ObjectNode notification = MAPPER.createObjectNode();
    notification.put("jsonrpc", "2.0");
    notification.put("method", "notifications/initialized");
    writeFrame(MAPPER.writeValueAsString(notification));
  }

  private void start() throws Exception {
    if (process != null && process.isAlive()) return;
    List<String> command = new ArrayList<>();
    command.add(config.command());
    command.addAll(config.args());
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
    builder.environment().putAll(config.env());
    process = builder.start();
    reader = process.getInputStream();
    writer = process.getOutputStream();
  }

  JsonNode request(String method, JsonNode params) throws Exception {
    int id = nextId++;
    ObjectNode body = MAPPER.createObjectNode();
    body.put("jsonrpc", "2.0");
    body.put("id", id);
    body.put("method", method);
    body.set("params", params);
    writeFrame(MAPPER.writeValueAsString(body));

    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (reader.available() <= 0) {
        Thread.sleep(25);
        continue;
      }
      JsonNode response = MAPPER.readTree(readFrame());
      if (response.path("id").asInt(-1) != id) continue;
      if (response.has("error")) {
        throw new IllegalStateException(response.get("error").toString());
      }
      return response.path("result");
    }
    throw new IllegalStateException("Timed out waiting for MCP response from " + config.name());
  }

  void writeFrame(String json) throws Exception {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    if ("newline-json".equals(config.protocol())) {
      writer.write(body);
      writer.write('\n');
    } else {
      writer.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      writer.write(body);
    }
    writer.flush();
  }

  String readFrame() throws Exception {
    if ("newline-json".equals(config.protocol())) {
      return readLine();
    }

    int contentLength = -1;
    while (true) {
      String header = readLine();
      if (header.isEmpty()) break;
      int colon = header.indexOf(':');
      if (colon > 0 && "content-length".equalsIgnoreCase(header.substring(0, colon).trim())) {
        contentLength = Integer.parseInt(header.substring(colon + 1).trim());
      }
    }
    if (contentLength < 0) {
      throw new IllegalStateException("MCP content-length response missing Content-Length header");
    }
    byte[] body = reader.readNBytes(contentLength);
    if (body.length != contentLength) {
      throw new IllegalStateException("MCP response ended before full Content-Length body was read");
    }
    return new String(body, StandardCharsets.UTF_8);
  }

  private String readLine() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    while (true) {
      int value = reader.read();
      if (value < 0) {
        if (out.size() == 0) return "";
        break;
      }
      if (value == '\n') break;
      if (value != '\r') out.write(value);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  @Override
  public void close() throws Exception {
    if (process == null) return;
    process.destroy();
    process.waitFor(2, TimeUnit.SECONDS);
    if (process.isAlive()) process.destroyForcibly();
  }
}
