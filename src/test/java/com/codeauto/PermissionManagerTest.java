package com.codeauto;

import com.codeauto.permissions.PermissionManager;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionPrompt;
import com.codeauto.permissions.PermissionRequest;
import com.codeauto.permissions.PermissionResponse;
import com.codeauto.permissions.PermissionStore;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionManagerTest {
  @Test
  void blocksKnownDangerousCommands() throws Exception {
    PermissionManager permissions = new PermissionManager(Path.of("").toAbsolutePath(),
        new PermissionStore(Files.createTempFile("permissions", ".json")),
        request -> PermissionDecision.DENY_ONCE);

    assertFalse(permissions.canRun("git", List.of("reset", "--hard")));
    assertFalse(permissions.canRun("npm", List.of("publish")));
    assertTrue(permissions.canRun("git", List.of("status")));
  }

  @Test
  void canPersistAllowedCommandDecision() throws Exception {
    Path storePath = Files.createTempFile("permissions-persist", ".json");
    Path cwd = Path.of("").toAbsolutePath();
    PermissionManager first = new PermissionManager(cwd, new PermissionStore(storePath),
        request -> PermissionDecision.ALLOW_ALWAYS);

    assertTrue(first.canRun("git", List.of("reset", "--hard")));

    PermissionManager second = new PermissionManager(cwd, new PermissionStore(storePath),
        request -> PermissionDecision.DENY_ONCE);

    assertTrue(second.canRun("git", List.of("reset", "--hard")));
  }

  @Test
  void capturesDenyWithFeedbackForNextToolResult() throws Exception {
    PermissionManager permissions = new PermissionManager(Path.of("").toAbsolutePath(),
        new PermissionStore(Files.createTempFile("permissions-feedback", ".json")),
        new PermissionPrompt() {
          @Override
          public PermissionDecision ask(PermissionRequest request) {
            return PermissionDecision.DENY_WITH_FEEDBACK;
          }

          @Override
          public PermissionResponse askDetailed(PermissionRequest request) {
            return new PermissionResponse(PermissionDecision.DENY_WITH_FEEDBACK, "Use git status instead.");
          }
        });

    assertFalse(permissions.canRun("git", List.of("reset", "--hard")));
    assertTrue(permissions.formatLastDenialFeedback().contains("Use git status instead."));
    assertTrue(permissions.formatLastDenialFeedback().isBlank());
  }

  @Test
  void defaultConsolePromptAllowsWorkspaceEditsWhenNoConsoleIsAvailable() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-permission-edit");
    PermissionManager permissions = new PermissionManager(cwd,
        new PermissionStore(Files.createTempFile("permissions-edit", ".json")),
        new com.codeauto.permissions.ConsolePermissionPrompt());

    assertTrue(permissions.canWrite(cwd.resolve("notes.md")));
    assertFalse(permissions.canWrite(cwd.resolve("..").resolve("outside.md")));
  }

  @Test
  void supportsWildcardCommandPermissionRules() throws Exception {
    Path storePath = Files.createTempFile("permissions-command-rules", ".json");
    PermissionStore store = new PermissionStore(storePath);
    PermissionStore.Data data = new PermissionStore.Data();
    data.allowedCommandPatterns.add("Bash(python scripts/*)");
    data.deniedCommandPatterns.add("Bash(git push --force*)");
    store.write(data);

    PermissionManager permissions = new PermissionManager(Path.of("").toAbsolutePath(), store,
        request -> PermissionDecision.DENY_ONCE);

    assertTrue(permissions.canRun("python", List.of("scripts/build.py")));
    assertFalse(permissions.canRun("git", List.of("push", "--force")));
  }

  @Test
  void supportsWildcardEditPermissionRules() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-permission-rules");
    Path storePath = Files.createTempFile("permissions-edit-rules", ".json");
    PermissionStore store = new PermissionStore(storePath);
    PermissionStore.Data data = new PermissionStore.Data();
    data.allowedEditPatterns.add("Edit(src/*.java)");
    data.deniedEditPatterns.add("Edit(src/Secret*)");
    store.write(data);

    PermissionManager permissions = new PermissionManager(cwd, store,
        request -> PermissionDecision.DENY_ONCE);

    assertTrue(permissions.canWrite(cwd.resolve("src").resolve("Main.java")));
    assertFalse(permissions.canWrite(cwd.resolve("src").resolve("Secret.java")));
    assertFalse(permissions.canWrite(cwd.resolve("README.md")));
  }

  @Test
  void describesPermissionStorageAndRuleCounts() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-permission-describe");
    Path storePath = Files.createTempFile("permissions-describe", ".json");
    PermissionStore store = new PermissionStore(storePath);
    PermissionStore.Data data = new PermissionStore.Data();
    data.allowedCommandPatterns.add("Bash(npm run *)");
    data.deniedEditPatterns.add("Edit(secret/*)");
    store.write(data);

    PermissionManager permissions = new PermissionManager(cwd, store,
        request -> PermissionDecision.DENY_ONCE);

    String description = permissions.describePermissions();
    assertTrue(description.contains(storePath.toAbsolutePath().normalize().toString()));
    assertTrue(description.contains("workspace=" + cwd.toAbsolutePath().normalize()));
    assertTrue(description.contains("allowedCommandPatterns=1"));
    assertTrue(description.contains("deniedEditPatterns=1"));
  }
}
