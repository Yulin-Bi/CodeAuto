package com.codeauto;

import com.codeauto.manage.ManagementStore;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementStoreTest {
  @Test
  void writesAndReadsMcpConfig() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-management-home");
    System.setProperty("codeauto.home", home.toString());
    ManagementStore store = new ManagementStore();
    var mcp = store.readMcp();
    mcp.putObject("local").put("command", "node");

    store.writeMcp(mcp);

    assertTrue(store.readMcp().has("local"));
  }

  @Test
  void mcpAddStoresProtocolAndEnv() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-management-mcp-add-home");
    System.setProperty("codeauto.home", home.toString());

    int exit = new CommandLine(new com.codeauto.cli.McpCommand()).execute(
        "add",
        "--protocol", "newline-json",
        "--env", "TOKEN=abc",
        "--env", "MODE=test",
        "local",
        "node",
        "server.js");

    var config = new ManagementStore().readMcp();
    assertEquals(0, exit);
    assertEquals("newline-json", config.path("local").path("protocol").asText());
    assertEquals("abc", config.path("local").path("env").path("TOKEN").asText());
    assertEquals("test", config.path("local").path("env").path("MODE").asText());
  }

  @Test
  void mcpLoginAndLogoutManageTokenFile() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-management-mcp-token-home");
    System.setProperty("codeauto.home", home.toString());

    int loginExit = new CommandLine(new com.codeauto.cli.McpCommand()).execute(
        "login", "local", "--token", "secret-token");

    ManagementStore store = new ManagementStore();
    assertEquals(0, loginExit);
    assertEquals("secret-token", store.readMcpTokens().path("local").asText());

    int logoutExit = new CommandLine(new com.codeauto.cli.McpCommand()).execute("logout", "local");

    assertEquals(0, logoutExit);
    assertTrue(!store.readMcpTokens().has("local"));
  }
}
