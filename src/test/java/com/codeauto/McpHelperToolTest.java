package com.codeauto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.permissions.PermissionStore;
import com.codeauto.tool.ToolContext;
import com.codeauto.tools.DefaultTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHelperToolTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void defaultToolsIncludeMcpResourceAndPromptHelpers() {
    Set<String> names = DefaultTools.create().list().stream()
        .map(tool -> tool.name())
        .collect(Collectors.toSet());

    assertTrue(names.contains("list_mcp_resources"));
    assertTrue(names.contains("read_mcp_resource"));
    assertTrue(names.contains("list_mcp_prompts"));
    assertTrue(names.contains("get_mcp_prompt"));
  }

  @Test
  void readResourceReportsUnknownServer() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-mcp-helper-test");
    Path home = Files.createTempDirectory("codeauto-mcp-helper-home");
    System.setProperty("codeauto.home", home.toString());

    var result = DefaultTools.create().execute("read_mcp_resource",
        MAPPER.createObjectNode().put("server", "missing").put("uri", "file://example"),
        new ToolContext(cwd, new PermissionManager(cwd, new PermissionStore(Files.createTempFile("permissions-mcp-helper", ".json")),
            request -> PermissionDecision.ALLOW_ONCE)));

    assertFalse(result.ok());
    assertTrue(result.output().contains("Unknown MCP server: missing"));
  }
}
