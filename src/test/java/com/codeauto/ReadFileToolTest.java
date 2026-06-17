package com.codeauto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.permissions.PermissionStore;
import com.codeauto.tool.ToolContext;
import com.codeauto.tools.DefaultTools;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFileToolTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void missingFileReturnsToolErrorInsteadOfThrowing() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-read-file-missing");
    PermissionManager permissions = new PermissionManager(
        cwd,
        new PermissionStore(Files.createTempFile("permissions-read-file", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);

    var result = DefaultTools.create().execute("read_file",
        MAPPER.createObjectNode().put("path", "missing.txt"),
        new ToolContext(cwd, permissions));

    assertFalse(result.ok());
    assertTrue(result.output().contains("File not found"), result.output());
  }
}
