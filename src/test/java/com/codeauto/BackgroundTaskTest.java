package com.codeauto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.background.BackgroundTaskRegistry;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.permissions.PermissionStore;
import com.codeauto.tool.ToolContext;
import com.codeauto.tools.DefaultTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundTaskTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final String previousHome = System.getProperty("codeauto.home");

  @AfterEach
  void restoreHome() {
    if (previousHome == null) {
      System.clearProperty("codeauto.home");
    } else {
      System.setProperty("codeauto.home", previousHome);
    }
  }

  @Test
  void canStartAndListBackgroundTask() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-bg-home");
    System.setProperty("codeauto.home", home.toString());
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
  void managedAppAppearsWithAppIdAndCanBeInspected() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-managed-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-bg-managed-home");
    System.setProperty("codeauto.home", home.toString());
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg-managed", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    String command = System.getProperty("os.name").toLowerCase().contains("win")
        ? "ping -n 60 127.0.0.1"
        : "sleep 60";

    var registry = DefaultTools.create();
    var start = registry.execute("run_command",
        MAPPER.createObjectNode().put("command", command).put("background", true).put("app_id", "managed-backend"),
        new ToolContext(temp, permissions));
    var inspect = registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "inspect").put("app_id", "managed-backend"),
        new ToolContext(temp, permissions));

    assertTrue(start.ok(), start.output());
    assertTrue(start.output().contains("app=managed-backend"), start.output());
    assertTrue(inspect.ok(), inspect.output());
    assertTrue(inspect.output().contains("app=managed-backend"), inspect.output());

    registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "cancel").put("app_id", "managed-backend"),
        new ToolContext(temp, permissions));
  }

  @Test
  void duplicateManagedAppIdIsRejectedWhileRunning() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-duplicate-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-bg-duplicate-home");
    System.setProperty("codeauto.home", home.toString());
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg-duplicate", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    String command = System.getProperty("os.name").toLowerCase().contains("win")
        ? "ping -n 60 127.0.0.1"
        : "sleep 60";

    var registry = DefaultTools.create();
    var first = registry.execute("run_command",
        MAPPER.createObjectNode().put("command", command).put("background", true).put("app_id", "dup-backend"),
        new ToolContext(temp, permissions));
    var second = registry.execute("run_command",
        MAPPER.createObjectNode().put("command", command).put("background", true).put("app_id", "dup-backend"),
        new ToolContext(temp, permissions));

    assertTrue(first.ok(), first.output());
    assertFalse(second.ok());
    assertTrue(second.output().contains("Managed app already running"), second.output());

    registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "cancel").put("app_id", "dup-backend"),
        new ToolContext(temp, permissions));
  }

  @Test
  void managedAppCanBeRestartedByAppId() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-restart-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-bg-restart-home");
    System.setProperty("codeauto.home", home.toString());
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg-restart", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    String command = System.getProperty("os.name").toLowerCase().contains("win")
        ? "ping -n 60 127.0.0.1"
        : "sleep 60";

    var registry = DefaultTools.create();
    var start = registry.execute("run_command",
        MAPPER.createObjectNode().put("command", command).put("background", true).put("app_id", "restart-backend"),
        new ToolContext(temp, permissions));
    assertTrue(start.ok(), start.output());
    long firstPid = Long.parseLong(start.output().substring(start.output().lastIndexOf("pid=") + 4).trim());

    var restart = registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "restart").put("app_id", "restart-backend"),
        new ToolContext(temp, permissions));

    assertTrue(restart.ok(), restart.output());
    long restartedPid = Long.parseLong(restart.output().substring(restart.output().lastIndexOf("pid=") + 4).trim());
    assertNotEquals(firstPid, restartedPid, restart.output());

    registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "cancel").put("app_id", "restart-backend"),
        new ToolContext(temp, permissions));
  }

  @Test
  void cancelBackgroundTask() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-cancel-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-bg-cancel-home");
    System.setProperty("codeauto.home", home.toString());
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
    String taskId = output.substring("Started background task ".length(), output.indexOf(' ', "Started background task ".length()));
    var cancel = registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "cancel").put("task_id", taskId),
        new ToolContext(temp, permissions));

    assertTrue(cancel.ok(), cancel.output());
    assertTrue(cancel.output().contains("Cancelled"), cancel.output());
  }

  @Test
  void inspectNonexistentTaskReturnsError() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-inspect-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-bg-inspect-home");
    System.setProperty("codeauto.home", home.toString());
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg-inspect", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);

    var result = DefaultTools.create().execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "inspect").put("task_id", "nonexistent"),
        new ToolContext(temp, permissions));

    assertFalse(result.ok());
    assertTrue(result.output().contains("not found"), result.output());
  }

  @Test
  void managedAppStatePersistsAcrossRegistryInstances() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-persist-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-bg-persist-home");
    System.setProperty("codeauto.home", home.toString());
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg-persist", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    String command = System.getProperty("os.name").toLowerCase().contains("win")
        ? "ping -n 60 127.0.0.1"
        : "sleep 60";

    var registry = DefaultTools.create();
    var start = registry.execute("run_command",
        MAPPER.createObjectNode().put("command", command).put("background", true).put("app_id", "persist-backend"),
        new ToolContext(temp, permissions));

    assertTrue(start.ok(), start.output());

    BackgroundTaskRegistry reloaded = new BackgroundTaskRegistry();
    var restored = reloaded.getByAppId("persist-backend");

    assertNotNull(restored);
    assertEquals("persist-backend", restored.appId());
    assertEquals(command, restored.command());

    registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "cancel").put("app_id", "persist-backend"),
        new ToolContext(temp, permissions));
  }

  @Test
  void restartingStoppedManagedAppDoesNotCreateDuplicateEntries() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-reuse-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-bg-reuse-home");
    System.setProperty("codeauto.home", home.toString());
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg-reuse", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    String command = System.getProperty("os.name").toLowerCase().contains("win")
        ? "ping -n 60 127.0.0.1"
        : "sleep 60";

    var registry = DefaultTools.create();
    var first = registry.execute("run_command",
        MAPPER.createObjectNode().put("command", command).put("background", true).put("app_id", "reuse-backend"),
        new ToolContext(temp, permissions));
    assertTrue(first.ok(), first.output());

    var cancel = registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "cancel").put("app_id", "reuse-backend"),
        new ToolContext(temp, permissions));
    assertTrue(cancel.ok(), cancel.output());

    var second = registry.execute("run_command",
        MAPPER.createObjectNode().put("command", command).put("background", true).put("app_id", "reuse-backend"),
        new ToolContext(temp, permissions));
    assertTrue(second.ok(), second.output());

    var taskIds = BackgroundTaskRegistry.get().list().stream()
        .filter(task -> "reuse-backend".equals(task.appId()))
        .map(task -> task.id())
        .collect(Collectors.toList());
    assertEquals(1, taskIds.size(), taskIds.toString());

    registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "cancel").put("app_id", "reuse-backend"),
        new ToolContext(temp, permissions));
  }

  @Test
  void managedAppCanWaitForHealthAndRestartReady() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-health-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-bg-health-home");
    System.setProperty("codeauto.home", home.toString());
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg-health", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    int port = findFreePort();

    var registry = DefaultTools.create();
    var startInput = MAPPER.createObjectNode()
        .put("command", javaExecutable())
        .put("background", true)
        .put("app_id", "health-backend")
        .put("health_url", "http://127.0.0.1:" + port + "/")
        .put("health_port", port)
        .put("startup_timeout", 10);
    startInput.putArray("args")
        .add("-cp")
        .add(System.getProperty("java.class.path"))
        .add("com.codeauto.testsupport.ManagedAppServer")
        .add(String.valueOf(port));

    var start = registry.execute("run_command", startInput, new ToolContext(temp, permissions));
    assertTrue(start.ok(), start.output());
    assertTrue(start.output().contains("health=ready"), start.output());

    var inspect = registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "inspect").put("app_id", "health-backend"),
        new ToolContext(temp, permissions));
    assertTrue(inspect.ok(), inspect.output());
    assertTrue(inspect.output().contains("[ready]"), inspect.output());

    var restart = registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "restart").put("app_id", "health-backend"),
        new ToolContext(temp, permissions));
    assertTrue(restart.ok(), restart.output());
    assertTrue(restart.output().contains("health=ready"), restart.output());

    registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "cancel").put("app_id", "health-backend"),
        new ToolContext(temp, permissions));
  }

  @Test
  void managedAppFailsWhenHealthCheckDoesNotComeUp() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-bg-health-fail-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-bg-health-fail-home");
    System.setProperty("codeauto.home", home.toString());
    PermissionManager permissions = new PermissionManager(temp, new PermissionStore(Files.createTempFile("permissions-bg-health-fail", ".json")),
        request -> PermissionDecision.ALLOW_ONCE);
    int port = findFreePort();

    var registry = DefaultTools.create();
    var startInput = MAPPER.createObjectNode()
        .put("command", javaExecutable())
        .put("background", true)
        .put("app_id", "health-fail-backend")
        .put("health_port", port)
        .put("startup_timeout", 1);
    startInput.putArray("args")
        .add("-version");

    var start = registry.execute("run_command", startInput, new ToolContext(temp, permissions));
    assertFalse(start.ok(), start.output());
    assertTrue(start.output().contains("readiness check"), start.output());

    var inspect = registry.execute("background_tasks",
        MAPPER.createObjectNode().put("operation", "inspect").put("app_id", "health-fail-backend"),
        new ToolContext(temp, permissions));
    assertTrue(inspect.ok(), inspect.output());
    assertTrue(inspect.output().contains("status=failed"), inspect.output());
  }

  private static String javaExecutable() {
    Path home = Path.of(System.getProperty("java.home"));
    String binary = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    return home.resolve("bin").resolve(binary).toString();
  }

  private static int findFreePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
