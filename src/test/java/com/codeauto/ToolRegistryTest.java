package com.codeauto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.permissions.PermissionPrompt;
import com.codeauto.permissions.PermissionRequest;
import com.codeauto.permissions.PermissionResponse;
import com.codeauto.permissions.PermissionStore;
import com.codeauto.tool.ToolContext;
import com.codeauto.tools.DefaultTools;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void editFileReplacesText() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-tools-test");
    Files.writeString(temp.resolve("hello.txt"), "hello old world");

    var result = DefaultTools.create().execute("edit_file",
        MAPPER.createObjectNode()
            .put("path", "hello.txt")
            .put("oldText", "old")
            .put("newText", "new"),
        new ToolContext(temp, allowingPermissions(temp)));

    assertTrue(result.ok());
    assertTrue(result.output().contains("---"));
    assertTrue(Files.readString(temp.resolve("hello.txt")).contains("new"));
  }

  @Test
  void patchFileAppliesSimpleUnifiedPatch() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-patch-test");
    Files.writeString(temp.resolve("hello.txt"), "alpha\nbeta\ngamma\n");

    String patch = """
        --- a/hello.txt
        +++ b/hello.txt
        @@
         alpha
        -beta
        +delta
         gamma
        """;

    var result = DefaultTools.create().execute("patch_file",
        MAPPER.createObjectNode().put("patch", patch),
        new ToolContext(temp, allowingPermissions(temp)));

    assertTrue(result.ok(), result.output());
    assertTrue(Files.readString(temp.resolve("hello.txt")).contains("delta"));
  }

  @Test
  void editDenialIncludesUserFeedback() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-tools-feedback-test");
    Files.writeString(temp.resolve("hello.txt"), "hello old world");
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-tools-feedback", ".json")),
        new PermissionPrompt() {
          @Override
          public PermissionDecision ask(PermissionRequest request) {
            return PermissionDecision.DENY_WITH_FEEDBACK;
          }

          @Override
          public PermissionResponse askDetailed(PermissionRequest request) {
            return new PermissionResponse(PermissionDecision.DENY_WITH_FEEDBACK, "Do not edit generated files.");
          }
        });

    var result = DefaultTools.create().execute("edit_file",
        MAPPER.createObjectNode()
            .put("path", "hello.txt")
            .put("oldText", "old")
            .put("newText", "new"),
        new ToolContext(temp, permissions));

    assertTrue(!result.ok());
    assertTrue(result.output().contains("Do not edit generated files."), result.output());
  }

  private static PermissionManager allowingPermissions(java.nio.file.Path root) throws Exception {
    return new PermissionManager(root, new PermissionStore(Files.createTempFile("permissions-tools", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
  }
}
