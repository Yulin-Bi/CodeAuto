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
import com.codeauto.tools.RunCommandTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandToolTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void splitsQuotedCommandArguments() {
    assertEquals(
        List.of("git", "commit", "-m", "hello world", "--author=a b"),
        RunCommandTool.splitCommand("git commit -m \"hello world\" '--author=a b'"));
  }

  @Test
  void rejectsUnclosedQuotes() {
    assertThrows(IllegalArgumentException.class, () -> RunCommandTool.splitCommand("echo \"unterminated"));
  }

  @Test
  void runsCommandWithExplicitArgsArray() throws Exception {
    Path temp = Files.createTempDirectory("codeauto-run-command-test");
    PermissionManager permissions = new PermissionManager(
        temp,
        new PermissionStore(Files.createTempFile("permissions-run-command", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();

    var result = DefaultTools.create().execute("run_command",
        MAPPER.createObjectNode()
            .put("command", javaExecutable)
            .set("args", MAPPER.createArrayNode().add("-version")),
        new ToolContext(temp, permissions));

    assertTrue(result.ok(), result.output());
    assertTrue(result.output().toLowerCase().contains("version"), result.output());
  }

  @Test
  void runsShellSnippetThroughPlatformShell() throws Exception {
    Path temp = Files.createTempDirectory("codeauto-run-command-shell-test");
    PermissionManager permissions = new PermissionManager(
        temp,
        new PermissionStore(Files.createTempFile("permissions-run-command-shell", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);

    var result = DefaultTools.create().execute("run_command",
        MAPPER.createObjectNode().put("command", "echo hello && echo there"),
        new ToolContext(temp, permissions));

    assertTrue(result.ok(), result.output());
    assertTrue(result.output().contains("hello"), result.output());
    assertTrue(result.output().contains("there"), result.output());
  }

  @Test
  void commandWithCustomTimeoutSucceeds() throws Exception {
    Path temp = Files.createTempDirectory("codeauto-run-command-timeout-test");
    PermissionManager permissions = new PermissionManager(
        temp,
        new PermissionStore(Files.createTempFile("permissions-run-command-timeout", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);

    var result = DefaultTools.create().execute("run_command",
        MAPPER.createObjectNode().put("command", "echo hello").put("timeout", 10),
        new ToolContext(temp, permissions));

    assertTrue(result.ok(), result.output());
    assertTrue(result.output().contains("hello"), result.output());
  }

  @Test
  void runsWindowsShellBuiltinsThroughCmd() throws Exception {
    if (!System.getProperty("os.name").toLowerCase().contains("win")) {
      return;
    }
    Path temp = Files.createTempDirectory("codeauto-run-command-windows-builtin");
    PermissionManager permissions = new PermissionManager(
        temp,
        new PermissionStore(Files.createTempFile("permissions-run-command-windows-builtin", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);

    var result = DefaultTools.create().execute("run_command",
        MAPPER.createObjectNode().put("command", "dir"),
        new ToolContext(temp, permissions));

    assertTrue(result.ok(), result.output());
  }

  @Test
  void commandWithVeryShortTimeoutFails() throws Exception {
    Path temp = Files.createTempDirectory("codeauto-run-command-timeout-fail-test");
    PermissionManager permissions = new PermissionManager(
        temp,
        new PermissionStore(Files.createTempFile("permissions-run-command-timeout-fail", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    // Use an invisible-blocking command: on Windows, "choice /t 10 /d y /n" waits 10 seconds
    // but may not be available. Use a subshell that waits for stdin (never receives input).
    // Use a command that blocks longer than the 1s timeout
    // On Windows: use START /B to launch a background process that waits, then wait for it
    String command = System.getProperty("os.name").toLowerCase().contains("win")
        ? "powershell -Command \"Start-Sleep -Seconds 10\""
        : "cat";

    var result = DefaultTools.create().execute("run_command",
        MAPPER.createObjectNode().put("command", command).put("timeout", 1),
        new ToolContext(temp, permissions));

    assertFalse(result.ok());
    assertTrue(result.output().toLowerCase().contains("timed out"), result.output());
  }

  @Test
  void commandDenialIncludesUserFeedback() throws Exception {
    Path temp = Files.createTempDirectory("codeauto-run-command-feedback-test");
    PermissionManager permissions = new PermissionManager(
        temp,
        new PermissionStore(Files.createTempFile("permissions-run-command-feedback", ".json")),
        new PermissionPrompt() {
          @Override
          public PermissionDecision ask(PermissionRequest request) {
            return PermissionDecision.DENY_WITH_FEEDBACK;
          }

          @Override
          public PermissionResponse askDetailed(PermissionRequest request) {
            return new PermissionResponse(PermissionDecision.DENY_WITH_FEEDBACK, "Please inspect before mutating.");
          }
        });

    var result = DefaultTools.create().execute("run_command",
        MAPPER.createObjectNode().put("command", "git reset --hard"),
        new ToolContext(temp, permissions));

    assertTrue(!result.ok());
    assertTrue(result.output().contains("Please inspect before mutating."), result.output());
  }
}
