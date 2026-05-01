package com.codeauto;

import com.codeauto.manage.ManagementStore;
import com.codeauto.mcp.McpService;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpServiceTest {
  @Test
  void readsConfiguredServers() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-mcp-home");
    System.setProperty("codeauto.home", home.toString());
    ManagementStore store = new ManagementStore();
    var config = store.readMcp();
    var entry = config.putObject("local");
    entry.put("command", "node");
    entry.putArray("args").add("server.js");
    store.writeMcp(config);

    var servers = new McpService(store).configuredServers();

    assertEquals(1, servers.size());
    assertEquals("local", servers.getFirst().name());
    assertEquals("auto", servers.getFirst().protocol());
  }

  @Test
  void readsWrappedUserMcpServersConfig() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-mcp-wrapped-home");
    System.setProperty("codeauto.home", home.toString());
    ManagementStore store = new ManagementStore();
    var config = store.readMcp();
    var entry = config.putObject("mcpServers").putObject("wrapped");
    entry.put("command", "node");
    entry.putArray("args").add("wrapped.js");
    store.writeMcp(config);

    var servers = new McpService(store).configuredServers();

    assertEquals(1, servers.size());
    assertEquals("wrapped", servers.getFirst().name());
    assertEquals("wrapped.js", servers.getFirst().args().getFirst());
  }

  @Test
  void normalizesConfiguredProtocols() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-mcp-protocol-home");
    System.setProperty("codeauto.home", home.toString());
    ManagementStore store = new ManagementStore();
    var config = store.readMcp();
    var stdio = config.putObject("stdio");
    stdio.put("protocol", "stdio");
    stdio.put("command", "node");
    var newline = config.putObject("newline");
    newline.put("protocol", "newline-json");
    newline.put("command", "node");
    store.writeMcp(config);

    var servers = new McpService(store).configuredServers();

    assertEquals("auto", servers.getFirst().protocol());
    assertEquals("newline-json", servers.get(1).protocol());
    assertEquals(java.util.List.of("content-length", "newline-json"), McpService.protocolCandidates("auto"));
    assertEquals(java.util.List.of("content-length"), McpService.protocolCandidates("content-length"));
  }

  @Test
  void readsConfiguredServerEnvironment() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-mcp-env-home");
    System.setProperty("codeauto.home", home.toString());
    ManagementStore store = new ManagementStore();
    var config = store.readMcp();
    var entry = config.putObject("local");
    entry.put("command", "node");
    var env = entry.putObject("env");
    env.put("TOKEN", "abc");
    env.put("LITERAL", "$THIS_ENV_VAR_SHOULD_NOT_EXIST_FOR_CODEAUTO_TESTS");
    store.writeMcp(config);

    var server = new McpService(store).configuredServers().getFirst();

    assertEquals("abc", server.env().get("TOKEN"));
    assertEquals("$THIS_ENV_VAR_SHOULD_NOT_EXIST_FOR_CODEAUTO_TESTS", server.env().get("LITERAL"));
  }

  @Test
  void injectsStoredTokenIntoServerEnvironment() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-mcp-token-env-home");
    System.setProperty("codeauto.home", home.toString());
    ManagementStore store = new ManagementStore();
    var config = store.readMcp();
    var entry = config.putObject("local");
    entry.put("command", "node");
    store.writeMcp(config);
    var tokens = store.readMcpTokens();
    tokens.put("local", "token-value");
    store.writeMcpTokens(tokens);

    var server = new McpService(store).configuredServers().getFirst();

    assertEquals("token-value", server.env().get("MCP_BEARER_TOKEN"));
    assertEquals("token-value", server.env().get("MCP_AUTH_TOKEN"));
  }

  @Test
  void explicitEnvironmentDoesNotGetOverwrittenByStoredToken() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-mcp-token-explicit-env-home");
    System.setProperty("codeauto.home", home.toString());
    ManagementStore store = new ManagementStore();
    var config = store.readMcp();
    var entry = config.putObject("local");
    entry.put("command", "node");
    entry.putObject("env").put("MCP_BEARER_TOKEN", "explicit");
    store.writeMcp(config);
    var tokens = store.readMcpTokens();
    tokens.put("local", "token-value");
    store.writeMcpTokens(tokens);

    var server = new McpService(store).configuredServers().getFirst();

    assertEquals("explicit", server.env().get("MCP_BEARER_TOKEN"));
    assertEquals("token-value", server.env().get("MCP_AUTH_TOKEN"));
  }

  @Test
  void mergesProjectMcpJsonWithoutOverridingUserConfig() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-mcp-project-home");
    java.nio.file.Path cwd = Files.createTempDirectory("codeauto-mcp-project");
    System.setProperty("codeauto.home", home.toString());
    ManagementStore store = new ManagementStore();
    var config = store.readMcp();
    var user = config.putObject("shared");
    user.put("command", "node");
    user.putArray("args").add("user.js");
    store.writeMcp(config);
    Files.writeString(cwd.resolve(".mcp.json"), """
        {
          "mcpServers": {
            "shared": { "command": "node", "args": ["project.js"] },
            "project": { "command": "python", "args": ["server.py"] }
          }
        }
        """);

    var servers = new McpService(store, cwd).configuredServers();

    assertEquals(2, servers.size());
    assertEquals("shared", servers.getFirst().name());
    assertEquals("user.js", servers.getFirst().args().getFirst());
    assertEquals("project", servers.get(1).name());
    assertEquals("server.py", servers.get(1).args().getFirst());
  }
}
