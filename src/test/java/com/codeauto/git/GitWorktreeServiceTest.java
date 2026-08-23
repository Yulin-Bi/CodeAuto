package com.codeauto.git;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitWorktreeServiceTest {
  @Test
  void createsDiscoversAndRollsBackManagedWorktree() throws Exception {
    Path repo = Files.createTempDirectory("codeauto-worktree-repo");
    Path managed = Files.createTempDirectory("codeauto-worktrees");
    git(repo, "init");
    git(repo, "config", "user.email", "test@codeauto.local");
    git(repo, "config", "user.name", "CodeAuto Test");
    Files.writeString(repo.resolve("README.md"), "root\n");
    git(repo, "add", "README.md");
    git(repo, "commit", "-m", "initial");

    var service = new GitWorktreeService(repo, managed);
    var created = service.create("abc123", "HEAD");

    assertTrue(Files.isDirectory(created.path()));
    assertEquals("codeauto/session-abc123", created.branch());
    assertEquals("initial", created.commitSubject());
    assertEquals("CodeAuto Test", created.commitAuthor());
    assertEquals("initial", created.recentCommits().getFirst().subject());
    assertTrue(created.managed());
    assertTrue(service.isRegistered(created.path()));
    assertEquals(2, service.list().size());

    service.rollbackCreated(created);
    assertFalse(Files.exists(created.path()));
    assertEquals(1, service.list().size());
  }

  @Test
  void acceptsManualBranchNameAndRequiresForceForDirtyDeletion() throws Exception {
    Path repo = Files.createTempDirectory("codeauto-worktree-delete-repo");
    Path managed = Files.createTempDirectory("codeauto-worktree-delete-root");
    git(repo, "init");
    git(repo, "config", "user.email", "test@codeauto.local");
    git(repo, "config", "user.name", "CodeAuto Test");
    Files.writeString(repo.resolve("README.md"), "root\n");
    git(repo, "add", "README.md");
    git(repo, "commit", "-m", "initial");
    var service = new GitWorktreeService(repo, managed);
    var created = service.create("manual01", "HEAD", "feature/manual-name");
    Files.writeString(created.path().resolve("dirty.txt"), "dirty");

    assertEquals("codeauto/feature/manual-name", created.branch());
    assertThrows(IllegalStateException.class, () -> service.deleteManaged(created.path(), false));
    service.deleteManaged(created.path(), true);

    assertFalse(Files.exists(created.path()));
    assertEquals(1, service.list().size());
  }

  @Test
  void stagesDiffsCommitsAndPushesThroughOneWorktreeService() throws Exception {
    Path repo = Files.createTempDirectory("codeauto-git-actions-repo");
    Path managed = Files.createTempDirectory("codeauto-git-actions-root");
    git(repo, "init");
    git(repo, "config", "user.email", "test@codeauto.local");
    git(repo, "config", "user.name", "CodeAuto Test");
    Files.writeString(repo.resolve("README.md"), "root\n");
    git(repo, "add", "README.md");
    git(repo, "commit", "-m", "initial");
    var service = new GitWorktreeService(repo, managed);
    Files.writeString(repo.resolve("feature.txt"), "first line\n");

    var dirty = service.status(repo);
    assertEquals(1, dirty.files().size());
    assertTrue(dirty.files().getFirst().untracked());
    assertTrue(service.diff(repo, "feature.txt", false).contains("first line"));

    var staged = service.stage(repo, java.util.List.of("feature.txt"), false);
    assertTrue(staged.files().getFirst().staged());
    var unstaged = service.unstage(repo, java.util.List.of("feature.txt"), false);
    assertTrue(unstaged.files().getFirst().untracked());
    service.stage(repo, java.util.List.of(), true);

    var committed = service.commit(repo, "add feature");
    assertTrue(committed.status().files().isEmpty());
    Files.writeString(repo.resolve("README.md"), "changed\n");
    var tracked = service.status(repo).files().getFirst();
    assertEquals("README.md", tracked.path());
    assertEquals("M", tracked.worktreeStatus());
    assertTrue(tracked.unstaged());
    git(repo, "restore", "README.md");
    assertThrows(IllegalStateException.class, () -> service.push(repo, ""));
  }

  private static void git(Path cwd, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(cwd.toFile()).inheritIO().start();
    assertEquals(0, process.waitFor());
  }
}
