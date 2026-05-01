package com.codeauto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.permissions.PermissionStore;
import com.codeauto.tool.ToolContext;
import com.codeauto.tools.DefaultTools;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundTaskTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void canStartAndListBackgroundTask() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-test");
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    String command = System.getProperty("os.name").toLowerCase().contains("win")
        ? "cmd /c echo hello"
        : "sh -c echo hello";

    var registry = DefaultTools.create();
    var start = registry.execute("run_command",
        MAPPER.createObjectNode().put("command", command).put("background", true),
        new ToolContext(temp, permissions));
    var list = registry.execute("background_tasks", MAPPER.createObjectNode(), new ToolContext(temp, permissions));

    assertTrue(start.ok(), start.output());
    assertTrue(list.output().contains("command="));
  }

  @Test
  void cancelBackgroundTask() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-cancel-test");
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg-cancel", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    String command = System.getProperty("os.name").toLowerCase().contains("win")
        ? "ping -n 60 127.0.0.1"
        : "sleep 60";

    var registry = DefaultTools.create();
    var start = registry.execute("run_command",
        MAPPER.createObjectNode().put("command", command).put("background", true),
        new ToolContext(temp, permissions));

    assertTrue(start.ok(), start.output());
    // Extract task ID from output: "Started background task <id> pid=..."
    String output = start.output();
    assertTrue(output.contains("Started background task"), output);

    // Cancel the task via background_tasks tool
    String taskId = output.substring("Started background task ".length(), output.indexOf(" pid="));
    var cancel = registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "cancel").put("task_id", taskId),
        new ToolContext(temp, permissions));

    assertTrue(cancel.ok(), cancel.output());
    assertTrue(cancel.output().contains("Cancelled"), cancel.output());
  }

  @Test
  void inspectNonexistentTaskReturnsError() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-inspect-test");
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg-inspect", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);

    var result = DefaultTools.create().execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "inspect").put("task_id", "nonexistent"),
        new ToolContext(temp, permissions));

    assertFalse(result.ok());
    assertTrue(result.output().contains("not found"), result.output());
  }
}
