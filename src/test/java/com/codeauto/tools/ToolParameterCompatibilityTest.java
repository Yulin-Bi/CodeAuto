package com.codeauto.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.tool.ToolContext;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolParameterCompatibilityTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void readFileAcceptsFilePathAlias() throws Exception {
    var cwd = Files.createTempDirectory("codeauto-read-alias");
    Files.writeString(cwd.resolve("sample.md"), "hello");
    var input = MAPPER.createObjectNode().put("file_path", "sample.md");

    var result = new ReadFileTool().run(input, new ToolContext(cwd, new PermissionManager(cwd)));

    assertTrue(result.ok());
    assertTrue(result.output().contains("hello"));
  }

  @Test
  void writeFileWorksInNonInteractiveWorkspace() throws Exception {
    var cwd = Files.createTempDirectory("codeauto-write-tool");
    var input = MAPPER.createObjectNode()
        .put("path", "created.md")
        .put("content", "# Created\n");

    var result = new WriteFileTool().run(input, new ToolContext(cwd, new PermissionManager(cwd)));

    assertTrue(result.ok(), result.output());
    assertTrue(Files.readString(cwd.resolve("created.md")).contains("# Created"));
  }

  @Test
  void writeFileStillRejectsPathsOutsideWorkspace() throws Exception {
    var cwd = Files.createTempDirectory("codeauto-write-tool-root");
    var outside = Files.createTempDirectory("codeauto-write-tool-outside").resolve("blocked.md");
    var input = MAPPER.createObjectNode()
        .put("path", outside.toString())
        .put("content", "# Blocked\n");

    var result = new WriteFileTool().run(input, new ToolContext(cwd, new PermissionManager(cwd)));

    assertFalse(result.ok());
    assertFalse(Files.exists(outside));
  }

  @Test
  void modifyAndEditFilesWorkInNonInteractiveWorkspace() throws Exception {
    var cwd = Files.createTempDirectory("codeauto-modify-edit-tool");
    Files.writeString(cwd.resolve("notes.md"), "old value\n");

    var modifyInput = MAPPER.createObjectNode()
        .put("path", "notes.md")
        .put("content", "modified value\n");
    var modifyResult = new ModifyFileTool().run(modifyInput, new ToolContext(cwd, new PermissionManager(cwd)));

    assertTrue(modifyResult.ok(), modifyResult.output());
    assertTrue(Files.readString(cwd.resolve("notes.md")).contains("modified value"));

    var editInput = MAPPER.createObjectNode()
        .put("path", "notes.md")
        .put("oldText", "modified")
        .put("newText", "edited");
    var editResult = new EditFileTool().run(editInput, new ToolContext(cwd, new PermissionManager(cwd)));

    assertTrue(editResult.ok(), editResult.output());
    assertTrue(Files.readString(cwd.resolve("notes.md")).contains("edited value"));
  }

  @Test
  void patchFileHandlesCrLfAndTrailingWhitespace() throws Exception {
    var cwd = Files.createTempDirectory("codeauto-patch-crlf");
    Files.writeString(cwd.resolve("patchme.txt"), "first\r\noriginal content \r\nlast\r\n");
    var patch = """
        --- a/patchme.txt
        +++ b/patchme.txt
        @@ -1,3 +1,3 @@
         first
        -original content
        +patched content
         last
        """;
    var input = MAPPER.createObjectNode()
        .put("path", "patchme.txt")
        .put("patch", patch);

    var result = new PatchFileTool().run(input, new ToolContext(cwd, new PermissionManager(cwd)));

    assertTrue(result.ok(), result.output());
    String after = Files.readString(cwd.resolve("patchme.txt"));
    assertTrue(after.contains("first\r\npatched content\r\nlast\r\n"), after);
  }
}
