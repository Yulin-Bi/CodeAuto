package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.git.GitCheckpointService;
import com.codeauto.git.GitCheckpointService.CheckpointEntry;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CheckpointTool implements ToolDefinition {

  private final Kind kind;

  public CheckpointTool(Kind kind) {
    this.kind = kind;
  }

  @Override
  public String name() {
    return switch (kind) {
      case LIST -> "checkpoint_list";
      case RESTORE -> "checkpoint_restore";
    };
  }

  @Override
  public String description() {
    return switch (kind) {
      case LIST -> "List all git checkpoints created before each turn. Only available in git repositories.";
      case RESTORE -> "Restore files from a git checkpoint. By default shows a preview (list of changed files). "
          + "Use execute: true to perform the restore. Use file parameter to restore a single file.";
    };
  }

  @Override
  public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    if (kind == Kind.RESTORE) {
      props.set("hash", JsonSchemas.stringProp("Checkpoint commit hash to restore from"));
      props.set("file", JsonSchemas.stringProp("Optional: restore only this single file instead of entire workspace"));
      props.set("execute", JsonSchemas.booleanProp("Set to true to actually perform the restore (default: false = preview only)"));
    }
    if (kind == Kind.RESTORE) {
      return JsonSchemas.required(schema, "hash");
    }
    return schema;
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    GitCheckpointService service = new GitCheckpointService();
    return switch (kind) {
      case LIST -> runList(service, context);
      case RESTORE -> runRestore(input, service, context);
    };
  }

  private ToolResult runList(GitCheckpointService service, ToolContext context) {
    if (!service.isGitRepo(context.cwd())) {
      return ToolResult.ok("Not a git repository. Checkpoints are unavailable.");
    }

    List<CheckpointEntry> entries = service.listCheckpoints(context.cwd());
    if (entries.isEmpty()) {
      return ToolResult.ok("No checkpoints found. Checkpoints are created automatically at the start of each turn.");
    }

    StringBuilder sb = new StringBuilder();
    sb.append(entries.size()).append(" checkpoint(s):\n");
    sb.append(String.format("%-10s %-20s %s\n", "HASH", "TIMESTAMP", "MESSAGE"));
    for (CheckpointEntry e : entries) {
      String shortHash = e.hash().length() > 7 ? e.hash().substring(0, 7) : e.hash();
      sb.append(String.format("%-10s %-20s %s\n", shortHash, e.timestamp(), e.message()));
    }
    return ToolResult.ok(sb.toString().trim());
  }

  private ToolResult runRestore(JsonNode input, GitCheckpointService service, ToolContext context) throws Exception {
    if (!service.isGitRepo(context.cwd())) {
      return ToolResult.error("Not a git repository. Checkpoints are unavailable.");
    }

    String hash = JsonSchemas.text(input, "hash", "");
    if (hash.isBlank()) {
      return ToolResult.error("hash is required");
    }

    String file = JsonSchemas.text(input, "file", "");
    boolean execute = input != null && input.path("execute").asBoolean(false);

    if (!file.isBlank()) {
      // Single file restore
      return restoreSingleFile(service, context, hash, file, execute);
    }

    // Full workspace restore
    List<String> changedFiles = service.diffCheckpoint(context.cwd(), hash);

    if (!execute) {
      // Preview mode
      if (changedFiles.isEmpty()) {
        return ToolResult.ok("No files differ between current state and checkpoint " + shortHash(hash) + ".");
      }
      StringBuilder sb = new StringBuilder();
      sb.append("Preview: ").append(changedFiles.size()).append(" file(s) would be restored from checkpoint ")
          .append(shortHash(hash)).append(":\n");
      for (String f : changedFiles) {
        sb.append("  ").append(f).append("\n");
      }
      sb.append("\nTo execute the restore, call checkpoint_restore again with execute: true and hash: \"")
          .append(hash).append("\"");
      return ToolResult.ok(sb.toString());
    }

    // Execute restore
    Path workspace = context.cwd().toAbsolutePath().normalize();
    for (String changedFile : changedFiles) {
      Path filePath = workspace.resolve(changedFile).normalize();
      if (!filePath.startsWith(workspace)) {
        return ToolResult.error("Checkpoint restore path escapes workspace: " + changedFile);
      }
      if (!context.permissions().canWrite(filePath)) {
        return ToolResult.error("Write path is not allowed: " + changedFile
            + context.permissions().formatLastDenialFeedback());
      }
    }
    return service.restoreCheckpoint(context.cwd(), hash);
  }

  private ToolResult restoreSingleFile(GitCheckpointService service, ToolContext context,
      String hash, String relativeFile, boolean execute) throws Exception {
    Path file = context.cwd().resolve(relativeFile).normalize();
    Path workspace = context.cwd().toAbsolutePath().normalize();

    // Path traversal guard: reject paths that escape the workspace.
    if (!file.startsWith(workspace)) {
      return ToolResult.error("File path escapes workspace: " + relativeFile);
    }

    if (!execute) {
      // Preview mode: check read permission first.
      if (!context.permissions().canRead(file)) {
        return ToolResult.error("Read path is not allowed: " + relativeFile);
      }
      String before = Files.exists(file) ? Files.readString(file) : "(file does not exist)";
      return ToolResult.ok("Preview: would restore " + relativeFile + " from checkpoint " + shortHash(hash)
          + "\nCurrent content:\n" + before
          + "\n\nTo execute, call checkpoint_restore again with execute: true, hash: \"" + hash
          + "\", file: \"" + relativeFile + "\"");
    }

    // Execute mode: check write permission.
    if (!context.permissions().canWrite(file)) {
      return ToolResult.error("Write path is not allowed: " + relativeFile
          + context.permissions().formatLastDenialFeedback());
    }

    // Execute single-file restore
    ToolResult result = service.restoreFile(context.cwd(), relativeFile, hash);
    return result;
  }

  private static String shortHash(String hash) {
    return hash.length() > 7 ? hash.substring(0, 7) : hash;
  }

  public enum Kind { LIST, RESTORE }
}
