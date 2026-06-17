package com.codeauto.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.permissions.PermissionStore;
import com.codeauto.tool.ToolContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CheckpointToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static boolean gitAvailable;

  @BeforeAll
  static void checkGitAvailable() {
    try {
      ProcessBuilder pb = new ProcessBuilder("git", "--version");
      pb.redirectErrorStream(true);
      Process p = pb.start();
      p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      gitAvailable = p.exitValue() == 0;
    } catch (Exception e) {
      gitAvailable = false;
    }
  }

  @Test
  void checkpointListNotGitRepo() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-checkpoint-nongit");
    try {
      var result = DefaultTools.create().execute("checkpoint_list",
          MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(result.ok());
      assertTrue(result.output().contains("Not a git repository"), result.output());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void checkpointListInGitRepo() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-checkpoint-list");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("test.txt"), "hello");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      // Create a checkpoint via the service first
      com.codeauto.git.GitCheckpointService service = new com.codeauto.git.GitCheckpointService();
      service.createCheckpoint(cwd, 1);

      var result = DefaultTools.create().execute("checkpoint_list",
          MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(result.ok(), result.output());
      assertTrue(result.output().contains("checkpoint"), result.output());
      assertTrue(result.output().contains("turn 1"), result.output());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void checkpointRestorePreviewShowsFiles() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-checkpoint-preview");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("data.txt"), "original");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      com.codeauto.git.GitCheckpointService service = new com.codeauto.git.GitCheckpointService();
      var hashOpt = service.createCheckpoint(cwd, 1);
      assertTrue(hashOpt.isPresent());
      String hash = hashOpt.get();

      // Modify file
      Files.writeString(cwd.resolve("data.txt"), "modified");

      // Preview restore (execute: false is default)
      var preview = DefaultTools.create().execute("checkpoint_restore",
          MAPPER.createObjectNode().put("hash", hash),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(preview.ok(), preview.output());
      assertTrue(preview.output().contains("Preview"), preview.output());
      assertTrue(preview.output().contains("data.txt"), preview.output());
      // File should NOT be restored
      assertEquals("modified", Files.readString(cwd.resolve("data.txt")),
          "File should not be changed in preview mode");
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void checkpointRestoreExecuteRestoresFile() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-checkpoint-execute");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("data.txt"), "original");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      com.codeauto.git.GitCheckpointService service = new com.codeauto.git.GitCheckpointService();
      var hashOpt = service.createCheckpoint(cwd, 1);
      assertTrue(hashOpt.isPresent());
      String hash = hashOpt.get();

      // Modify file
      Files.writeString(cwd.resolve("data.txt"), "modified");

      // Execute restore
      var restore = DefaultTools.create().execute("checkpoint_restore",
          MAPPER.createObjectNode()
              .put("hash", hash)
              .put("execute", true),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(restore.ok(), restore.output());

      assertEquals("original", Files.readString(cwd.resolve("data.txt")));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void checkpointRestoreSingleFilePreview() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-checkpoint-single");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("a.txt"), "A original");
      Files.writeString(cwd.resolve("b.txt"), "B original");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      com.codeauto.git.GitCheckpointService service = new com.codeauto.git.GitCheckpointService();
      var hashOpt = service.createCheckpoint(cwd, 1);
      assertTrue(hashOpt.isPresent());
      String hash = hashOpt.get();

      Files.writeString(cwd.resolve("a.txt"), "A modified");

      // Preview single file restore
      var preview = DefaultTools.create().execute("checkpoint_restore",
          MAPPER.createObjectNode()
              .put("hash", hash)
              .put("file", "a.txt"),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertTrue(preview.ok(), preview.output());
      assertTrue(preview.output().contains("Preview"), preview.output());
      // File should NOT be restored
      assertEquals("A modified", Files.readString(cwd.resolve("a.txt")));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void checkpointRestoreRequiresHash() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-checkpoint-nohash");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("f.txt"), "content");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      var result = DefaultTools.create().execute("checkpoint_restore",
          MAPPER.createObjectNode(),
          new ToolContext(cwd, allowingPermissions(cwd)));
      assertFalse(result.ok());
      assertTrue(result.output().contains("hash is required"), result.output());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void checkpointRestoreExecuteHonorsWritePermissions() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-checkpoint-permissions");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("data.txt"), "original");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      com.codeauto.git.GitCheckpointService service = new com.codeauto.git.GitCheckpointService();
      var hashOpt = service.createCheckpoint(cwd, 1);
      assertTrue(hashOpt.isPresent());
      String hash = hashOpt.get();

      Files.writeString(cwd.resolve("data.txt"), "modified");

      Path storePath = Files.createTempFile("permissions-chkpt-deny", ".json");
      PermissionStore store = new PermissionStore(storePath);
      PermissionStore.Data data = new PermissionStore.Data();
      data.deniedEditPatterns.add("Edit(data.txt)");
      store.write(data);
      PermissionManager permissions = new PermissionManager(cwd, store, request -> PermissionDecision.ALLOW_ONCE);

      var restore = DefaultTools.create().execute("checkpoint_restore",
          MAPPER.createObjectNode()
              .put("hash", hash)
              .put("execute", true),
          new ToolContext(cwd, permissions));
      assertFalse(restore.ok(), restore.output());
      assertTrue(restore.output().contains("Write path is not allowed"), restore.output());
      assertEquals("modified", Files.readString(cwd.resolve("data.txt")),
          "Denied restore must not change the file");
    } finally {
      deleteRecursively(cwd);
    }
  }

  private static PermissionManager allowingPermissions(Path root) throws Exception {
    return new PermissionManager(root, new PermissionStore(Files.createTempFile("permissions-chkpt", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
  }

  private static void runGit(Path cwd, String... args) throws Exception {
    List<String> cmd = new java.util.ArrayList<>();
    cmd.add("git");
    cmd.addAll(java.util.Arrays.asList(args));
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
  }

  private static void deleteRecursively(Path path) {
    try {
      if (Files.isDirectory(path)) {
        try (var paths = Files.walk(path)) {
          paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try {
              if (Files.isDirectory(p) && p.getFileName().toString().equals(".git")) {
                try (var inner = Files.walk(p)) {
                  inner.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                    try { f.toFile().setWritable(true); Files.deleteIfExists(f); } catch (Exception ignored) {}
                  });
                }
              } else {
                Files.deleteIfExists(p);
              }
            } catch (Exception ignored) {}
          });
        }
      } else {
        Files.deleteIfExists(path);
      }
    } catch (Exception ignored) {}
  }
}
