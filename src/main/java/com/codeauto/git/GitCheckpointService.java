package com.codeauto.git;

import com.codeauto.tool.ToolResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Pre-turn git checkpoint service. Uses git CLI via ProcessBuilder — no JGit dependency.
 * Checkpoints are stored under refs/codeauto/checkpoints using write-tree + commit-tree so
 * the working tree and index are never modified.
 */
public class GitCheckpointService {

  private static final String CHECKPOINT_REF = "refs/codeauto/checkpoints";
  private static final Duration GIT_TIMEOUT = Duration.ofSeconds(15);

  public boolean isGitRepo(Path cwd) {
    try {
      ProcessResult result = run(cwd, "git", "rev-parse", "--git-dir");
      return result.exitCode() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Create a checkpoint of the working tree. Returns the new commit hash.
   * Algorithm:
   *   1. git add -A (stage everything)
   *   2. git write-tree (create tree object)
   *   3. git rev-parse refs/codeauto/checkpoints (find parent, if any)
   *   4. git commit-tree <tree> [-p <parent>] -m "..." (create commit)
   *   5. git update-ref refs/codeauto/checkpoints <commit> (update ref)
   *   6. git reset HEAD (unstage everything, restore original index)
   */
  public Optional<String> createCheckpoint(Path cwd, int turnNumber) {
    if (!isGitRepo(cwd)) {
      return Optional.empty();
    }
    try {
      // Stage everything (including untracked and deletions)
      ProcessResult add = run(cwd, "git", "add", "-A");
      if (add.exitCode() != 0) {
        return Optional.empty();
      }

      // Create tree object
      ProcessResult writeTree = run(cwd, "git", "write-tree");
      if (writeTree.exitCode() != 0 || writeTree.stdout().isBlank()) {
        // Unstage even on failure
        run(cwd, "git", "reset", "HEAD");
        return Optional.empty();
      }
      String treeHash = writeTree.stdout().trim();

      // Find parent commit (if previous checkpoint exists)
      String parentHash = null;
      ProcessResult revParse = run(cwd, "git", "rev-parse", CHECKPOINT_REF);
      if (revParse.exitCode() == 0 && !revParse.stdout().isBlank()) {
        parentHash = revParse.stdout().trim();
      }

      // Create commit object
      List<String> commitArgs = new ArrayList<>();
      commitArgs.add("git");
      commitArgs.add("commit-tree");
      commitArgs.add(treeHash);
      if (parentHash != null) {
        commitArgs.add("-p");
        commitArgs.add(parentHash);
      }
      commitArgs.add("-m");
      commitArgs.add("codeauto: checkpoint before turn " + turnNumber + " at " + Instant.now());

      ProcessResult commit = run(cwd, commitArgs.toArray(new String[0]));
      if (commit.exitCode() != 0 || commit.stdout().isBlank()) {
        run(cwd, "git", "reset", "HEAD");
        return Optional.empty();
      }
      String commitHash = commit.stdout().trim();

      // Update checkpoint ref
      ProcessResult updateRef = run(cwd, "git", "update-ref", CHECKPOINT_REF, commitHash);
      if (updateRef.exitCode() != 0) {
        run(cwd, "git", "reset", "HEAD");
        return Optional.empty();
      }

      // Restore original index (unstage everything)
      run(cwd, "git", "reset", "HEAD");

      return Optional.of(commitHash);
    } catch (Exception e) {
      System.err.println("[CodeAuto] Checkpoint creation failed: " + e.getMessage());
      // Best-effort: try to unstage
      try {
        run(cwd, "git", "reset", "HEAD");
      } catch (Exception ignored) {}
      return Optional.empty();
    }
  }

  public List<CheckpointEntry> listCheckpoints(Path cwd) {
    if (!isGitRepo(cwd)) {
      return List.of();
    }
    try {
      ProcessResult log = run(cwd, "git", "log",
          "--format=%H|%s|%aI", CHECKPOINT_REF);
      if (log.exitCode() != 0 || log.stdout().isBlank()) {
        return List.of();
      }
      List<CheckpointEntry> entries = new ArrayList<>();
      for (String line : log.stdout().split("\n")) {
        line = line.trim();
        if (line.isBlank()) continue;
        String[] parts = line.split("\\|", 3);
        if (parts.length >= 3) {
          entries.add(new CheckpointEntry(parts[0], parts[1], parts[2]));
        }
      }
      return entries;
    } catch (Exception e) {
      return List.of();
    }
  }

  public ToolResult restoreCheckpoint(Path cwd, String hash) {
    if (!isGitRepo(cwd)) {
      return ToolResult.error("Not a git repository. Checkpoints are unavailable.");
    }
    try {
      ProcessResult checkout = run(cwd, "git", "checkout", hash, "--", ".");
      if (checkout.exitCode() != 0) {
        return ToolResult.error("Failed to restore checkpoint " + shortHash(hash) + ": "
            + checkout.stderr());
      }
      return ToolResult.ok("Restored working tree to checkpoint " + shortHash(hash));
    } catch (Exception e) {
      return ToolResult.error("Checkpoint restore failed: " + e.getMessage());
    }
  }

  public ToolResult restoreFile(Path cwd, String relativeFilePath, String hash) {
    if (!isGitRepo(cwd)) {
      return ToolResult.error("Not a git repository. Checkpoints are unavailable.");
    }
    try {
      ProcessResult checkout = run(cwd, "git", "checkout", hash, "--", relativeFilePath);
      if (checkout.exitCode() != 0) {
        return ToolResult.error("Failed to restore file " + relativeFilePath + " from checkpoint "
            + shortHash(hash) + ": " + checkout.stderr());
      }
      return ToolResult.ok("Restored " + relativeFilePath + " from checkpoint " + shortHash(hash));
    } catch (Exception e) {
      return ToolResult.error("File restore failed: " + e.getMessage());
    }
  }

  public List<String> diffCheckpoint(Path cwd, String hash) {
    if (!isGitRepo(cwd)) {
      return List.of();
    }
    try {
      ProcessResult diff = run(cwd, "git", "diff", "--name-only", hash);
      if (diff.exitCode() != 0) return List.of();
      List<String> files = new ArrayList<>();
      for (String line : diff.stdout().split("\n")) {
        line = line.trim();
        if (!line.isBlank()) files.add(line);
      }
      Collections.sort(files);
      return files;
    } catch (Exception e) {
      return List.of();
    }
  }

  /**
   * Check if a working tree has uncommitted changes (including untracked files).
   */
  public boolean hasUncommittedChanges(Path cwd) {
    if (!isGitRepo(cwd)) return false;
    try {
      ProcessResult status = run(cwd, "git", "status", "--porcelain");
      return status.exitCode() == 0 && !status.stdout().isBlank();
    } catch (Exception e) {
      return false;
    }
  }

  private static String shortHash(String hash) {
    return hash.length() > 7 ? hash.substring(0, 7) : hash;
  }

  private static ProcessResult run(Path cwd, String... command) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(cwd.toFile());
    builder.redirectErrorStream(false);
    Process process = builder.start();
    if (!process.waitFor(GIT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      throw new RuntimeException("Git command timed out: " + String.join(" ", command));
    }
    String stdout = new String(process.getInputStream().readAllBytes()).trim();
    String stderr = new String(process.getErrorStream().readAllBytes()).trim();
    return new ProcessResult(process.exitValue(), stdout, stderr);
  }

  public record CheckpointEntry(String hash, String message, String timestamp) {}

  private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
