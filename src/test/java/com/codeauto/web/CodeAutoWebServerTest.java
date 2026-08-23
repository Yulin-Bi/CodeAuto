package com.codeauto.web;

import com.codeauto.config.RuntimeConfig;
import com.codeauto.model.MockModelAdapter;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.tools.DefaultTools;
import com.codeauto.todo.TodoStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAutoWebServerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private String previousHome;

  @BeforeEach
  void isolateCodeAutoHome() throws Exception {
    previousHome = System.getProperty("codeauto.home");
    System.setProperty("codeauto.home", Files.createTempDirectory("codeauto-web-test-home").toString());
  }

  @AfterEach
  void restoreCodeAutoHome() {
    if (previousHome == null) System.clearProperty("codeauto.home");
    else System.setProperty("codeauto.home", previousHome);
  }

  @Test
  void reusesTheOnlyBlankRootSession() throws Exception {
    var cwd = Files.createTempDirectory("codeauto-web-session");
    try (var server = new CodeAutoWebServer(cwd, RuntimeConfig.DEFAULTS, DefaultTools.create(),
        new MockModelAdapter(), new PermissionManager(cwd))) {
      int port = server.start(0);
      JsonNode first = postSession(port);
      JsonNode second = postSession(port);

      assertEquals(first.path("active").asText(), second.path("active").asText());
      assertEquals(1, second.path("sessions").size());
    }
  }

  @Test
  void refusesToDeleteAParentUntilItsChildIsDeleted() throws Exception {
    var cwd = Files.createTempDirectory("codeauto-web-delete-tree");
    try (var server = new CodeAutoWebServer(cwd, RuntimeConfig.DEFAULTS, DefaultTools.create(),
        new MockModelAdapter(), new PermissionManager(cwd))) {
      int port = server.start(0);
      String parentId = postSession(port).path("active").asText();
      JsonNode forked = request(port, "/api/sessions/" + parentId + "/fork", "POST",
          "{\"isolated\":false,\"title\":\"child\"}");
      String childId = forked.path("active").asText();

      assertEquals(409, deleteStatus(port, "/api/sessions/" + parentId));
      delete(port, "/api/sessions/" + childId);
      JsonNode deletedParent = delete(port, "/api/sessions/" + parentId);
      assertEquals(0, deletedParent.path("sessions").size());
    }
  }

  @Test
  void isolatedForkBindsARealWorktreeAndExposesItInState() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-web-worktree");
    git(cwd, "init");
    git(cwd, "config", "user.email", "test@codeauto.local");
    git(cwd, "config", "user.name", "CodeAuto Test");
    Files.writeString(cwd.resolve("README.md"), "root\n");
    git(cwd, "add", "README.md");
    git(cwd, "commit", "-m", "initial");
    try (var server = new CodeAutoWebServer(cwd, RuntimeConfig.DEFAULTS, DefaultTools.create(),
        new MockModelAdapter(), new PermissionManager(cwd))) {
      int port = server.start(0);
      String parentId = postSession(port).path("active").asText();
      JsonNode forked = request(port, "/api/sessions/" + parentId + "/fork", "POST",
          "{\"isolated\":true,\"title\":\"manual session\",\"branchName\":\"feature/manual-web\"}");
      String childId = forked.path("active").asText();
      JsonNode child = findSession(forked, childId);

      assertEquals(parentId, child.path("parentSessionId").asText());
      assertEquals("manual session", child.path("title").asText());
      assertEquals("codeauto/feature/manual-web", child.path("gitBranch").asText());
      assertTrue(child.path("worktreeAvailable").asBoolean());
      Path worktreePath = Path.of(child.path("worktreePath").asText());
      assertTrue(Files.isDirectory(worktreePath));
      assertEquals(2, forked.path("worktrees").size());
      Files.writeString(worktreePath.resolve("child-only.txt"), "isolated");
      JsonNode content = get(port, "/api/files/content?sessionId=" + childId + "&path=child-only.txt");
      assertEquals("isolated", content.path("content").asText());
      new TodoStore(worktreePath).add("worktree todo", "testing worktree todo");
      JsonNode todos = get(port, "/api/todos?sessionId=" + childId);
      assertEquals("worktree todo", todos.path("groups").get(0).path("entries").get(0).path("content").asText());
      git(cwd, "worktree", "remove", "--force", worktreePath.toString());
      assertEquals(409, requestStatus(port, "/api/sessions/" + childId + "/messages",
          "{\"content\":\"must not fall back\"}"));
      JsonNode deleted = delete(port, "/api/sessions/" + childId);
      assertTrue(findSessionOrNull(deleted, childId) == null);
    }
  }

  @Test
  void exposesScopedGitStatusDiffStageCommitAndPushProtection() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-web-git-actions");
    git(cwd, "init");
    git(cwd, "config", "user.email", "test@codeauto.local");
    git(cwd, "config", "user.name", "CodeAuto Test");
    Files.writeString(cwd.resolve("README.md"), "root\n");
    git(cwd, "add", "README.md");
    git(cwd, "commit", "-m", "initial");
    try (var server = new CodeAutoWebServer(cwd, RuntimeConfig.DEFAULTS, DefaultTools.create(),
        new MockModelAdapter(), new PermissionManager(cwd))) {
      int port = server.start(0);
      String sessionId = postSession(port).path("active").asText();
      Files.writeString(cwd.resolve("web-change.txt"), "from web\n");

      JsonNode status = get(port, "/api/sessions/" + sessionId + "/git/status");
      assertEquals("web-change.txt", status.path("files").get(0).path("path").asText());
      JsonNode diff = get(port, "/api/sessions/" + sessionId
          + "/git/diff?path=web-change.txt&staged=false");
      assertTrue(diff.path("diff").asText().contains("from web"));
      JsonNode staged = request(port, "/api/sessions/" + sessionId + "/git/stage", "POST",
          "{\"paths\":[\"web-change.txt\"]}");
      assertTrue(staged.path("files").get(0).path("staged").asBoolean());
      JsonNode committed = request(port, "/api/sessions/" + sessionId + "/git/commit", "POST",
          "{\"message\":\"web commit\"}");
      assertEquals(0, committed.path("files").size());
      assertEquals(409, requestStatus(port, "/api/sessions/" + sessionId + "/git/push", "{}"));
    }
  }

  @Test
  void firstMessageCompletesAndIsPersistedForAWebDraft() throws Exception {
    var cwd = Files.createTempDirectory("codeauto-web-first-message");
    try (var server = new CodeAutoWebServer(cwd, RuntimeConfig.DEFAULTS, DefaultTools.create(),
        new MockModelAdapter(), new PermissionManager(cwd))) {
      int port = server.start(0);
      String sessionId = postSession(port).path("active").asText();
      assertEquals(200, requestStatus(port, "/api/sessions/" + sessionId + "/messages",
          "{\"content\":\"请回复一句测试\"}"));
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      JsonNode transcript = MAPPER.createArrayNode();
      while (System.nanoTime() < deadline) {
        transcript = get(port, "/api/sessions/" + sessionId + "/transcript");
        if (transcript.toString().contains("Mock") || transcript.size() > 2) break;
        Thread.sleep(20);
      }
      assertTrue(transcript.size() > 2, "首条消息应在 Web 会话中收到 Agent 回复");
    }
  }

  private JsonNode postSession(int port) throws Exception {
    return request(port, "/api/sessions", "POST", "{}");
  }

  private JsonNode request(int port, String path, String method, String body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return MAPPER.readTree(response.body());
  }

  private JsonNode get(int port, String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    return MAPPER.readTree(response.body());
  }

  private JsonNode delete(int port, String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).DELETE().build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    return MAPPER.readTree(response.body());
  }

  private int requestStatus(int port, String path, String body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private int deleteStatus(int port, String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).DELETE().build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static JsonNode findSession(JsonNode state, String id) {
    JsonNode found = findSessionOrNull(state, id);
    if (found != null) return found;
    throw new AssertionError("session not found: " + id);
  }

  private static JsonNode findSessionOrNull(JsonNode state, String id) {
    for (JsonNode session : state.path("sessions")) if (id.equals(session.path("id").asText())) return session;
    return null;
  }

  private static void git(Path cwd, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(cwd.toFile()).inheritIO().start();
    assertEquals(0, process.waitFor());
  }
}
