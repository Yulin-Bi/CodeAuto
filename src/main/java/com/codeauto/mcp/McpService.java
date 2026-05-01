package com.codeauto.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.manage.ManagementStore;
import com.codeauto.tool.ToolDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpService {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ManagementStore store;
  private final Path cwd;

  public McpService() {
    this(new ManagementStore(), Path.of("").toAbsolutePath().normalize());
  }

  public McpService(ManagementStore store) {
    this(store, Path.of("").toAbsolutePath().normalize());
  }

  public McpService(ManagementStore store, Path cwd) {
    this.store = store;
    this.cwd = cwd.toAbsolutePath().normalize();
  }

  public List<McpServerConfig> configuredServers() {
    Map<String, McpServerConfig> servers = new LinkedHashMap<>();
    Map<String, String> tokens = readTokens();
    try {
      collectServers(store.readMcp(), servers, true, tokens);
    } catch (Exception ignored) {
      // MCP config is optional.
    }
    try {
      Path projectConfig = cwd.resolve(".mcp.json");
      if (Files.exists(projectConfig)) {
        collectServers(MAPPER.readTree(projectConfig.toFile()), servers, false, tokens);
      }
    } catch (Exception ignored) {
      // Project MCP config is optional.
    }
    return new ArrayList<>(servers.values());
  }

  public List<McpToolSummary> listTools() {
    List<McpToolSummary> tools = new ArrayList<>();
    for (McpServerConfig server : configuredServers()) {
      try {
        tools.addAll(runOnServer(server, new McpClientOperation<List<McpToolSummary>>() {
          @Override public List<McpToolSummary> runStdio(McpClient client) throws Exception { return client.listTools(); }
          @Override public List<McpToolSummary> runHttp(McpHttpClient client) throws Exception { return client.listTools(); }
        }));
      } catch (Exception error) {
        tools.add(new McpToolSummary(server.name(), "(error)", error.getMessage()));
      }
    }
    return tools;
  }

  public List<ToolDefinition> createBackedTools() {
    List<ToolDefinition> definitions = new ArrayList<>();
    for (McpServerConfig server : configuredServers()) {
      try {
        for (McpToolSummary tool : runOnServer(server, new McpClientOperation<List<McpToolSummary>>() {
          @Override public List<McpToolSummary> runStdio(McpClient client) throws Exception { return client.listTools(); }
          @Override public List<McpToolSummary> runHttp(McpHttpClient client) throws Exception { return client.listTools(); }
        })) {
          definitions.add(new McpBackedTool(server, tool));
        }
      } catch (Exception ignored) {
        // A failed server should not block local tools.
      }
    }
    return definitions;
  }

  /** Dispatch a call to the appropriate client (McpHttpClient for HTTP, McpClient for stdio). */
  public static <T> T runOnServer(McpServerConfig server, McpClientOperation<T> operation) throws Exception {
    if (server.isHttp()) {
      try (McpHttpClient client = new McpHttpClient(server)) {
        return operation.runHttp(client);
      }
    }
    return withProtocolFallback(server, operation);
  }

  private Map<String, String> readTokens() {
    Map<String, String> tokens = new LinkedHashMap<>();
    try {
      JsonNode raw = store.readMcpTokens();
      for (Iterator<String> it = raw.fieldNames(); it.hasNext();) {
        String name = it.next();
        String token = raw.path(name).asText("").trim();
        if (!token.isBlank()) tokens.put(name, token);
      }
    } catch (Exception ignored) {
      // Token file is optional.
    }
    return tokens;
  }

  private static void collectServers(
      JsonNode config,
      Map<String, McpServerConfig> servers,
      boolean overwrite,
      Map<String, String> tokens
  ) {
    JsonNode root = config.path("mcpServers").isObject() ? config.path("mcpServers") : config;
    for (Iterator<String> it = root.fieldNames(); it.hasNext();) {
      String name = it.next();
      JsonNode entry = root.get(name);
      if (!overwrite && servers.containsKey(name)) continue;

      // HTTP MCP server (has a url field instead of command)
      String url = text(entry, "url", "");
      if (!url.isBlank()) {
        Map<String, String> env = parseEnv(entry.path("env"), tokens.get(name));
        mergeHeaders(entry.path("headers"), env);
        servers.put(name, new McpServerConfig(name, "", List.of(), "auto", env, url));
        continue;
      }

      // Stdio MCP server
      String command = entry.path("command").asText("");
      if (command.isBlank()) continue;
      List<String> args = new ArrayList<>();
      for (JsonNode arg : entry.path("args")) args.add(arg.asText());
      String protocol = normalizeProtocol(entry.path("protocol").asText("auto"));
      servers.put(name, new McpServerConfig(name, command, args, protocol, parseEnv(entry.path("env"), tokens.get(name))));
    }
  }

  /** Merge MCP headers into the env map so they can be sent as HTTP headers by McpHttpClient. */
  static void mergeHeaders(JsonNode headers, Map<String, String> env) {
    if (headers == null || !headers.isObject()) return;
    for (Iterator<String> it = headers.fieldNames(); it.hasNext();) {
      String key = it.next();
      String value = headers.path(key).asText("");
      if (!key.isBlank() && !value.isBlank()) {
        // Prefix to distinguish from regular env vars; McpHttpClient strips the prefix.
        env.putIfAbsent("MCP_HEADER_" + key, value);
      }
    }
  }

  private static Map<String, String> parseEnv(JsonNode env, String token) {
    Map<String, String> values = new LinkedHashMap<>();
    if (env.isObject()) {
      for (Iterator<String> it = env.fieldNames(); it.hasNext();) {
        String key = it.next();
        values.put(key, interpolateEnv(env.path(key).asText("")));
      }
    }
    if (token != null && !token.isBlank()) {
      values.putIfAbsent("MCP_BEARER_TOKEN", token);
      values.putIfAbsent("MCP_AUTH_TOKEN", token);
    }
    return values;
  }

  private static String interpolateEnv(String value) {
    if (!value.startsWith("$") || value.length() <= 1) return value;
    String name = value.substring(1);
    return System.getenv().getOrDefault(name, value);
  }

  private static String normalizeProtocol(String protocol) {
    return switch (protocol) {
      case "auto" -> "auto";
      case "newline-json" -> "newline-json";
      case "content-length" -> "content-length";
      case "stdio", "" -> "auto";
      default -> "auto";
    };
  }

  public static List<String> protocolCandidates(String protocol) {
    return switch (normalizeProtocol(protocol)) {
      case "newline-json" -> List.of("newline-json");
      case "content-length" -> List.of("content-length");
      default -> List.of("content-length", "newline-json");
    };
  }

  public static <T> T withProtocolFallback(McpServerConfig server, McpClientOperation<T> operation) throws Exception {
    Exception lastError = null;
    for (String protocol : protocolCandidates(server.protocol())) {
      McpServerConfig attempt = new McpServerConfig(server.name(), server.command(), server.args(), protocol, server.env());
      try (McpClient client = new McpClient(attempt)) {
        return operation.runStdio(client);
      } catch (Exception error) {
        lastError = error;
      }
    }
    throw lastError == null ? new IllegalStateException("No MCP protocol candidates for " + server.name()) : lastError;
  }

  private static String text(JsonNode json, String field, String fallback) {
    JsonNode value = json.get(field);
    return value == null || value.isNull() ? fallback : value.asText();
  }

  /** Interface for MCP client operations supporting both HTTP and stdio transports. */
  @FunctionalInterface
  public interface McpClientOperation<T> {
    T runStdio(McpClient client) throws Exception;

    /** Default: HTTP transport calls the same method name. Override if needed. */
    default T runHttp(McpHttpClient client) throws Exception {
      throw new UnsupportedOperationException("HTTP transport not supported for this operation");
    }
  }
}
