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

  @Test
  void memoryToolsSaveListAndDeletePersistentMemory() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-memory-tool-home");
    java.nio.file.Path cwd = Files.createTempDirectory("codeauto-memory-tool-workspace");
    try {
      System.setProperty("codeauto.home", home.toString());
      var registry = DefaultTools.create();
      var context = new ToolContext(cwd, allowingPermissions(cwd));
      var save = registry.execute("save_memory",
          MAPPER.createObjectNode()
              .put("destination", "store")
              .put("type", "project")
              .put("title", "Tool memory")
              .put("content", "Remember this from a tool."),
          context);

      assertTrue(save.ok(), save.output());
      var list = registry.execute("list_memory", MAPPER.createObjectNode(), context);
      assertTrue(list.ok(), list.output());
      assertTrue(list.output().contains("Tool memory"));

      String id = list.output().split(" ")[0];
      var delete = registry.execute("delete_memory", MAPPER.createObjectNode().put("id", id), context);
      assertTrue(delete.ok(), delete.output());
    } finally {
      if (previousHome == null) {
        System.clearProperty("codeauto.home");
      } else {
        System.setProperty("codeauto.home", previousHome);
      }
    }
  }

  @Test
  void saveMemoryCanWriteProjectAndGlobalClaudeDestinations() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-memory-destination-home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-memory-destination-user-home");
    java.nio.file.Path cwd = Files.createTempDirectory("codeauto-memory-destination-workspace");
    try {
      System.setProperty("codeauto.home", home.toString());
      System.setProperty("user.home", userHome.toString());
      var registry = DefaultTools.create();
      var context = new ToolContext(cwd, allowingPermissions(cwd));

      var project = registry.execute("save_memory",
          MAPPER.createObjectNode()
              .put("destination", "project")
              .put("title", "Project rule")
              .put("content", "Run mvn test before finishing."),
          context);
      assertTrue(project.ok(), project.output());
      assertTrue(Files.readString(cwd.resolve("CLAUDE.md")).contains("Run mvn test"));

      var global = registry.execute("save_memory",
          MAPPER.createObjectNode()
              .put("destination", "global")
              .put("title", "User rule")
              .put("content", "Answer concisely."),
          context);
      assertTrue(global.ok(), global.output());
      assertTrue(Files.readString(userHome.resolve(".claude").resolve("CLAUDE.md")).contains("Answer concisely"));
    } finally {
      if (previousHome == null) {
        System.clearProperty("codeauto.home");
      } else {
        System.setProperty("codeauto.home", previousHome);
      }
      if (previousUserHome == null) {
        System.clearProperty("user.home");
      } else {
        System.setProperty("user.home", previousUserHome);
      }
    }
  }

  @Test
  void saveMemoryRequiresExplicitDestination() throws Exception {
    java.nio.file.Path cwd = Files.createTempDirectory("codeauto-memory-missing-destination");
    var result = DefaultTools.create().execute("save_memory",
        MAPPER.createObjectNode()
            .put("title", "No destination")
            .put("content", "Do not save implicitly."),
        new ToolContext(cwd, allowingPermissions(cwd)));

    assertTrue(!result.ok());
    assertTrue(result.output().contains("destination is required"), result.output());
  }

  private static PermissionManager allowingPermissions(java.nio.file.Path root) throws Exception {
    return new PermissionManager(root, new PermissionStore(Files.createTempFile("permissions-tools", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
  }
}
