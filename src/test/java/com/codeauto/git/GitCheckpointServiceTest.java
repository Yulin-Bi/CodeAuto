package com.codeauto.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitCheckpointServiceTest {

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
  void isGitRepoFalseForNonGitDir() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-git-check-nongit");
    try {
      GitCheckpointService service = new GitCheckpointService();
      assertFalse(service.isGitRepo(cwd));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void isGitRepoTrueForGitDir() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-git-check-isrepo");
    try {
      runGit(cwd, "init");
      GitCheckpointService service = new GitCheckpointService();
      assertTrue(service.isGitRepo(cwd));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void createCheckpointReturnsHash() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-git-checkpoint-hash");
    try {
      runGit(cwd, "init");
      // Create a file so there's something to snapshot
      Files.writeString(cwd.resolve("test.txt"), "hello checkpoint");
      // Need an initial commit for write-tree to work properly
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      GitCheckpointService service = new GitCheckpointService();
      Optional<String> hash = service.createCheckpoint(cwd, 1);
      assertTrue(hash.isPresent(), "Should return a commit hash");
      assertFalse(hash.get().isBlank());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void listCheckpointsReturnsEntries() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-git-checkpoint-list");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("a.txt"), "A");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      GitCheckpointService service = new GitCheckpointService();
      service.createCheckpoint(cwd, 1);
      // Modify and create second checkpoint
      Files.writeString(cwd.resolve("b.txt"), "B");
      service.createCheckpoint(cwd, 2);

      List<GitCheckpointService.CheckpointEntry> entries = service.listCheckpoints(cwd);
      assertTrue(entries.size() >= 2, "Should have at least 2 checkpoints, got: " + entries.size());

      // Entries should be in chronological order (git log default)
      assertTrue(entries.get(0).message().contains("turn 1") || entries.get(1).message().contains("turn 1"));
      assertTrue(entries.get(0).message().contains("turn 2") || entries.get(1).message().contains("turn 2"));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void restoreCheckpointRestoresFile() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-git-checkpoint-restore");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("data.txt"), "original");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      GitCheckpointService service = new GitCheckpointService();
      Optional<String> hash = service.createCheckpoint(cwd, 1);
      assertTrue(hash.isPresent());

      // Modify the file
      Files.writeString(cwd.resolve("data.txt"), "modified");
      assertEquals("modified", Files.readString(cwd.resolve("data.txt")));

      // Restore from checkpoint
      var result = service.restoreCheckpoint(cwd, hash.get());
      assertTrue(result.ok(), result.output());

      assertEquals("original", Files.readString(cwd.resolve("data.txt")));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void restoreFileRestoresSingleFile() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-git-checkpoint-file");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("a.txt"), "A original");
      Files.writeString(cwd.resolve("b.txt"), "B original");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      GitCheckpointService service = new GitCheckpointService();
      Optional<String> hash = service.createCheckpoint(cwd, 1);
      assertTrue(hash.isPresent());

      // Modify both files
      Files.writeString(cwd.resolve("a.txt"), "A modified");
      Files.writeString(cwd.resolve("b.txt"), "B modified");

      // Restore only a.txt
      var result = service.restoreFile(cwd, "a.txt", hash.get());
      assertTrue(result.ok(), result.output());

      assertEquals("A original", Files.readString(cwd.resolve("a.txt")));
      assertEquals("B modified", Files.readString(cwd.resolve("b.txt")), "b.txt should NOT be restored");
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void diffCheckpointShowsChangedFiles() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-git-checkpoint-diff");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("f.txt"), "v1");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      GitCheckpointService service = new GitCheckpointService();
      Optional<String> hash = service.createCheckpoint(cwd, 1);
      assertTrue(hash.isPresent());

      // Modify tracked file
      Files.writeString(cwd.resolve("f.txt"), "v2");
      // Create new file and add it (so it's tracked in the diff)
      Files.writeString(cwd.resolve("new.txt"), "new");
      runGit(cwd, "add", "new.txt");

      List<String> changed = service.diffCheckpoint(cwd, hash.get());
      assertTrue(changed.contains("f.txt"), "f.txt should be in diff: " + changed);
      assertTrue(changed.contains("new.txt"), "new.txt should be in diff: " + changed);
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void nonGitRepoReturnsGracefully() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-git-checkpoint-nongit");
    try {
      GitCheckpointService service = new GitCheckpointService();

      assertFalse(service.isGitRepo(cwd));
      assertEquals(Optional.empty(), service.createCheckpoint(cwd, 1));
      assertTrue(service.listCheckpoints(cwd).isEmpty());

      var restoreResult = service.restoreCheckpoint(cwd, "abc1234");
      assertFalse(restoreResult.ok());
      assertTrue(restoreResult.output().contains("Not a git repository"));

      var fileResult = service.restoreFile(cwd, "f.txt", "abc1234");
      assertFalse(fileResult.ok());
      assertTrue(fileResult.output().contains("Not a git repository"));

      assertTrue(service.diffCheckpoint(cwd, "abc1234").isEmpty());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void hasUncommittedChangesDetectsDirtyTree() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-git-checkpoint-dirty");
    try {
      runGit(cwd, "init");
      Files.writeString(cwd.resolve("f.txt"), "initial");
      runGit(cwd, "add", "-A");
      runGit(cwd, "commit", "-m", "initial");

      GitCheckpointService service = new GitCheckpointService();
      assertFalse(service.hasUncommittedChanges(cwd), "Clean tree should have no changes");

      Files.writeString(cwd.resolve("f.txt"), "modified");
      assertTrue(service.hasUncommittedChanges(cwd), "Modified file should be detected");
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void freshRepoNoCommitsWorks() throws Exception {
    assumeTrue(gitAvailable, "git not available on PATH");
    Path cwd = Files.createTempDirectory("codeauto-git-checkpoint-fresh");
    try {
      runGit(cwd, "init");
      // No commits yet, but git write-tree should still work with files staged
      Files.writeString(cwd.resolve("hello.txt"), "world");

      GitCheckpointService service = new GitCheckpointService();
      Optional<String> hash = service.createCheckpoint(cwd, 1);
      // Should work even without prior commits (creates root commit)
      assertTrue(hash.isPresent());
      assertFalse(hash.get().isBlank());
    } finally {
      deleteRecursively(cwd);
    }
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
              // Make sure .git files are writable before deleting
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
