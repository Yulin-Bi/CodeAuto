package com.codeauto.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.permissions.PermissionStore;
import com.codeauto.tool.ToolContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void undoListShowsActiveRecords() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-list-test");
    try {
      Files.writeString(cwd.resolve("hello.txt"), "original content");

      var registry = DefaultTools.create();
      var ctx = new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("call-001");

      // Write file to create undo record
      var write = registry.execute("write_file",
          MAPPER.createObjectNode().put("path", "hello.txt").put("content", "modified content"),
          ctx);
      assertTrue(write.ok(), write.output());

      // List
      var list = registry.execute("undo_list", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(list.ok(), list.output());
      assertTrue(list.output().contains("Wrote"), list.output());
      assertTrue(list.output().contains("hello.txt"), list.output());
      assertTrue(list.output().contains("active"), list.output());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoLatestRestoresContent() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-undo-test");
    try {
      Path file = cwd.resolve("hello.txt");
      Files.writeString(file, "original content");

      var registry = DefaultTools.create();
      var ctx = new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("call-001");

      // Write file to create undo record
      registry.execute("write_file",
          MAPPER.createObjectNode().put("path", "hello.txt").put("content", "modified content"),
          ctx);
      assertEquals("modified content", Files.readString(file));

      // Undo
      var undo = registry.execute("undo", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(undo.ok(), undo.output());
      assertTrue(undo.output().contains("Undid"), undo.output());

      // Verify file restored
      assertEquals("original content", Files.readString(file));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoWriteFileDeletesNewFile() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-delete-test");
    try {
      Path file = cwd.resolve("new-file.txt");
      assertFalse(Files.exists(file));

      var registry = DefaultTools.create();
      var ctx = new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("call-001");

      // Write a NEW file
      registry.execute("write_file",
          MAPPER.createObjectNode().put("path", "new-file.txt").put("content", "brand new"),
          ctx);
      assertTrue(Files.exists(file));

      // Undo — should delete the file because beforeContent was empty
      var undo = registry.execute("undo", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(undo.ok(), undo.output());

      // File should be deleted
      assertFalse(Files.exists(file), "New file should be deleted after undo");
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoEditFileRestoresOriginal() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-edit-test");
    try {
      Path file = cwd.resolve("greeting.txt");
      Files.writeString(file, "hello world");

      var registry = DefaultTools.create();
      var ctx = new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("call-001");

      registry.execute("edit_file",
          MAPPER.createObjectNode()
              .put("path", "greeting.txt")
              .put("oldText", "hello")
              .put("newText", "goodbye"),
          ctx);
      assertEquals("goodbye world", Files.readString(file));

      var undo = registry.execute("undo", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(undo.ok(), undo.output());

      assertEquals("hello world", Files.readString(file));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoModifyFileRestoresOriginal() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-modify-test");
    try {
      Path file = cwd.resolve("config.txt");
      Files.writeString(file, "original config");

      var registry = DefaultTools.create();
      var ctx = new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("call-001");

      registry.execute("modify_file",
          MAPPER.createObjectNode()
              .put("path", "config.txt")
              .put("content", "completely new config"),
          ctx);
      assertEquals("completely new config", Files.readString(file));

      var undo = registry.execute("undo", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(undo.ok(), undo.output());

      assertEquals("original config", Files.readString(file));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoPatchFileRestoresOriginal() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-patch-test");
    try {
      Files.writeString(cwd.resolve("data.txt"), "alpha\nbeta\ngamma\n");

      var registry = DefaultTools.create();
      var ctx = new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("call-001");

      String patch = """
          --- a/data.txt
          +++ b/data.txt
          @@
           alpha
          -beta
          +delta
           gamma
          """;
      registry.execute("patch_file", MAPPER.createObjectNode().put("patch", patch), ctx);
      assertTrue(Files.readString(cwd.resolve("data.txt")).contains("delta"));

      var undo = registry.execute("undo", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(undo.ok(), undo.output());

      String restored = Files.readString(cwd.resolve("data.txt"));
      assertTrue(restored.contains("beta"), restored);
      assertFalse(restored.contains("delta"), restored);
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoCreatesRedoRecord() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-redo-test");
    try {
      Path file = cwd.resolve("text.txt");
      Files.writeString(file, "original");

      var registry = DefaultTools.create();
      var ctx = new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("call-001");

      registry.execute("write_file",
          MAPPER.createObjectNode().put("path", "text.txt").put("content", "modified"),
          ctx);

      // First undo — creates redo record (toolName="undo") and marks original as undone
      var undo1 = registry.execute("undo", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(undo1.ok(), undo1.output());
      assertEquals("original", Files.readString(file));

      // Verify redo record exists in the list (with includeUndone=false via undo_list it may be included)
      var list = registry.execute("undo_list", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      // The redo record has toolName="undo" and is active
      assertTrue(list.output().contains("undo"), "Redo record should exist: " + list.output());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoAllReversesAllInReverseOrder() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-all-test");
    try {
      Path fileA = cwd.resolve("a.txt");
      Path fileB = cwd.resolve("b.txt");
      Path fileC = cwd.resolve("c.txt");
      Files.writeString(fileA, "A original");
      Files.writeString(fileB, "B original");
      Files.writeString(fileC, "C original");

      var registry = DefaultTools.create();

      // Modify all three files in order
      registry.execute("write_file",
          MAPPER.createObjectNode().put("path", "a.txt").put("content", "A modified"),
          new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("c-a"));
      registry.execute("write_file",
          MAPPER.createObjectNode().put("path", "b.txt").put("content", "B modified"),
          new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("c-b"));
      registry.execute("write_file",
          MAPPER.createObjectNode().put("path", "c.txt").put("content", "C modified"),
          new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("c-c"));

      // Undo all
      var undoAll = registry.execute("undo_all", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(undoAll.ok(), undoAll.output());
      assertTrue(undoAll.output().contains("Undid 3"), undoAll.output());

      // All should be restored
      assertEquals("A original", Files.readString(fileA));
      assertEquals("B original", Files.readString(fileB));
      assertEquals("C original", Files.readString(fileC));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoNonexistentIdReturnsError() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-badid-test");
    try {
      var result = DefaultTools.create().execute("undo",
          MAPPER.createObjectNode().put("id", "nonexistent"),
          new ToolContext(cwd, allowingPermissions(cwd)));

      assertFalse(result.ok());
      assertTrue(result.output().contains("not found"), result.output());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoWithNoRecordsReturnsMessage() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-empty-test");
    try {
      var result = DefaultTools.create().execute("undo",
          MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));

      assertTrue(result.ok());
      assertTrue(result.output().contains("No operations to undo"), result.output());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoAfterExternalModificationOverwrites() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-external-test");
    try {
      Path file = cwd.resolve("data.txt");
      Files.writeString(file, "original");

      var registry = DefaultTools.create();
      var ctx = new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("call-001");

      registry.execute("edit_file",
          MAPPER.createObjectNode()
              .put("path", "data.txt")
              .put("oldText", "original")
              .put("newText", "modified by agent"),
          ctx);

      // External modification (simulating user edit outside agent)
      Files.writeString(file, "modified externally by user");

      // Undo should overwrite external changes with stored beforeContent
      var undo = registry.execute("undo", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(undo.ok(), undo.output());

      assertEquals("original", Files.readString(file));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoListShowsUndoneStatus() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-status-test");
    try {
      Files.writeString(cwd.resolve("f.txt"), "before");
      var registry = DefaultTools.create();
      var ctx = new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("call-001");

      registry.execute("edit_file",
          MAPPER.createObjectNode()
              .put("path", "f.txt").put("oldText", "before").put("newText", "after"),
          ctx);

      // Before undo
      var list1 = registry.execute("undo_list", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(list1.output().contains("active"), list1.output());

      // Undo
      registry.execute("undo", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));

      // After undo — should show "undone"
      var list2 = registry.execute("undo_list", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(list2.output().contains("undone"), list2.output());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void multipleEditsToSameFileEachGetOwnUndoRecord() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-samefile-test");
    try {
      Path file = cwd.resolve("text.txt");
      Files.writeString(file, "v1");

      var registry = DefaultTools.create();

      registry.execute("edit_file",
          MAPPER.createObjectNode().put("path", "text.txt").put("oldText", "v1").put("newText", "v2"),
          new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("c1"));
      registry.execute("edit_file",
          MAPPER.createObjectNode().put("path", "text.txt").put("oldText", "v2").put("newText", "v3"),
          new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("c2"));

      assertEquals("v3", Files.readString(file));

      // Undo latest → back to v2
      registry.execute("undo", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertEquals("v2", Files.readString(file));

      // Undo again → back to v1
      registry.execute("undo", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertEquals("v1", Files.readString(file));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void undoAlreadyUndoneRecordReturnsError() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undotool-double-test");
    try {
      Files.writeString(cwd.resolve("f.txt"), "before");
      var registry = DefaultTools.create();
      var ctx = new ToolContext(cwd, allowingPermissions(cwd)).withToolCallId("call-001");

      registry.execute("edit_file",
          MAPPER.createObjectNode().put("path", "f.txt").put("oldText", "before").put("newText", "after"),
          ctx);

      // Get the undo record id from the list output (table format: ID column first)
      var list = registry.execute("undo_list", MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      String output = list.output();
      // Parse: first data line after header line ("ID  TOOL  STATUS...")
      // Split by whitespace, first token on the data line is the ID
      String[] lines = output.split("\n");
      String id = null;
      for (String line : lines) {
        String[] parts = line.trim().split("\\s+");
        // data lines: 8-char hex id, tool name, status, timestamp, file
        if (parts.length >= 5 && parts[0].matches("[a-f0-9]{8}")) {
          id = parts[0];
          break;
        }
      }
      assertTrue(id != null, "Should find an undo record id in output: " + output);

      // First undo
      var undo1 = registry.execute("undo", MAPPER.createObjectNode().put("id", id),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(undo1.ok(), undo1.output());

      // Second undo of same record
      var undo2 = registry.execute("undo", MAPPER.createObjectNode().put("id", id),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertFalse(undo2.ok());
      assertTrue(undo2.output().contains("already been undone"), undo2.output());
    } finally {
      deleteRecursively(cwd);
    }
  }

  private static PermissionManager allowingPermissions(Path root) throws Exception {
    return new PermissionManager(root, new PermissionStore(Files.createTempFile("permissions-tools", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
  }

  private static void deleteRecursively(Path path) {
    try {
      if (Files.isDirectory(path)) {
        try (var paths = Files.walk(path)) {
          paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
          });
        }
      } else {
        Files.deleteIfExists(path);
      }
    } catch (Exception ignored) {}
  }
}
