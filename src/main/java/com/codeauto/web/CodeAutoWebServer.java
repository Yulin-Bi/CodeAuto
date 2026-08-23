package com.codeauto.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.codeauto.config.RuntimeConfig;
import com.codeauto.config.ConfigLoader;
import com.codeauto.context.ContextStats;
import com.codeauto.core.AgentLoop;
import com.codeauto.core.AgentLoopListener;
import com.codeauto.core.ChatMessage;
import com.codeauto.core.ProviderUsage;
import com.codeauto.model.ModelAdapter;
import com.codeauto.model.AnthropicModelAdapter;
import com.codeauto.model.MockModelAdapter;
import com.codeauto.reflection.ReflectionService;
import com.codeauto.git.GitWorktreeService;
import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.session.SessionStore;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolRegistry;
import com.codeauto.todo.TodoStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small single-process Web surface. It deliberately reuses CodeAuto's Java runtime. */
public final class CodeAutoWebServer implements AutoCloseable {
  private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
  private final java.nio.file.Path cwd;
  private volatile RuntimeConfig runtime;
  private final ToolRegistry tools;
  private volatile ModelAdapter model;
  private final PermissionManager permissions;
  private final WebPermissionBroker permissionBroker = new WebPermissionBroker();
  private final SessionStore sessions;
  private final GitWorktreeService worktrees;
  private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
  private final Map<OutputStream, Object> subscribers = new ConcurrentHashMap<>();
  private final ExecutorService turns = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "codeauto-web-turn"); t.setDaemon(true); return t;
  });
  private HttpServer server;

  public CodeAutoWebServer(java.nio.file.Path cwd, RuntimeConfig runtime, ToolRegistry tools,
      ModelAdapter model, PermissionManager permissions) {
    this.cwd = cwd.toAbsolutePath().normalize(); this.runtime = runtime; this.tools = tools;
    this.model = model; this.permissions = permissions; this.sessions = new SessionStore(this.cwd);
    this.worktrees = new GitWorktreeService(this.cwd);
    this.permissionBroker.onRequest(approval -> publish("permission_request", approval.sessionId(),
        permissionJson(approval)));
  }

  public synchronized int start(int requestedPort) throws IOException {
    if (server != null) return server.getAddress().getPort();
    loadPersistedSessions();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", Math.max(0, requestedPort)), 0);
    server.createContext("/", this::handle); server.setExecutor(turns); server.start();
    return server.getAddress().getPort();
  }

  private void loadPersistedSessions() {
    try {
      Map<Path, GitWorktreeService.WorktreeInfo> registeredWorktrees = new java.util.HashMap<>();
      for (var worktree : worktrees.list()) registeredWorktrees.put(worktree.path(), worktree);
      for (SessionStore.SessionSummary summary : sessions.list()) {
        Conversation c = new Conversation(summary.id());
        c.title = summary.title(); c.titleLocked = true;
        c.parentSessionId = summary.parentSessionId(); c.forkBoundary = summary.forkBoundary();
        if (summary.worktreePath() != null) {
          Path storedPath = Path.of(summary.worktreePath()).toAbsolutePath().normalize();
          if (registeredWorktrees.containsKey(storedPath)) c.executionCwd = storedPath;
          else c.worktreeUnavailable = true;
          c.worktreePath = storedPath;
          c.gitBranch = summary.gitBranch(); c.baseCommit = summary.baseCommit();
        }
        c.messages.add(new ChatMessage.SystemMessage(systemPrompt(c.executionCwd)));
        List<ChatMessage> loaded = sessions.load(summary.id());
        c.messages.addAll(loaded);
        for (ChatMessage message : loaded) {
          if (message instanceof ChatMessage.UserMessage) c.turns++;
          if (message instanceof ChatMessage.AssistantToolCallMessage) c.toolCalls++;
          if (message instanceof ChatMessage.ToolResultMessage result && result.isError()) c.errors++;
          if (message instanceof ChatMessage.ContextSummaryMessage) c.compactions++;
        }
        c.savedCount = c.messages.size();
        c.trace.addAll(loadEvaluationTrace(summary.id()));
        conversations.putIfAbsent(summary.id(), c);
      }
    } catch (Exception ignored) {
      // A malformed historical session must not prevent the Web UI from starting.
    }
  }

  public int port() { return server == null ? -1 : server.getAddress().getPort(); }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      String path = URI.create(exchange.getRequestURI().toString()).getPath();
      if ("GET".equals(exchange.getRequestMethod()) && "/".equals(path)) { resource(exchange, "web/index.html", "text/html; charset=utf-8"); return; }
      if ("GET".equals(exchange.getRequestMethod()) && "/app.js".equals(path)) { resource(exchange, "web/app.js", "text/javascript; charset=utf-8"); return; }
      if ("GET".equals(exchange.getRequestMethod()) && "/styles.css".equals(path)) { resource(exchange, "web/styles.css", "text/css; charset=utf-8"); return; }
      if ("GET".equals(exchange.getRequestMethod()) && "/icons.svg".equals(path)) { resource(exchange, "web/icons.svg", "image/svg+xml; charset=utf-8"); return; }
      if ("GET".equals(exchange.getRequestMethod()) && "/api/state".equals(path)) { json(exchange, state()); return; }
      if ("GET".equals(exchange.getRequestMethod()) && "/api/settings".equals(path)) { json(exchange, settings()); return; }
      if ("GET".equals(exchange.getRequestMethod()) && "/api/worktrees".equals(path)) { json(exchange, worktreeState()); return; }
      if ("GET".equals(exchange.getRequestMethod()) && "/api/worktrees/graph".equals(path)) { json(exchange, worktreeGraphJson()); return; }
      if (path.startsWith("/api/permissions/") && "POST".equals(exchange.getRequestMethod())) {
        String id = path.substring("/api/permissions/".length());
        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        try {
          PermissionDecision decision = PermissionDecision.valueOf(body.path("decision").asText(""));
          WebPermissionBroker.AutoPolicy policy = WebPermissionBroker.AutoPolicy.valueOf(
              body.path("autoPolicy").asText("ASK"));
          var approval = permissionBroker.resolve(id, decision, body.path("feedback").asText(""), policy);
          publish("permission_resolved", approval.sessionId(),
              MAPPER.createObjectNode().put("requestId", id).put("decision", decision.name()));
          json(exchange, MAPPER.createObjectNode().put("ok", true));
        } catch (IllegalArgumentException invalid) {
          error(exchange, 400, invalid.getMessage());
        }
        return;
      }
      if (path.startsWith("/api/sessions/") && path.contains("/git/")) {
        handleGit(exchange, path); return;
      }
      if ("POST".equals(exchange.getRequestMethod()) && "/api/settings".equals(path)) {
        json(exchange, updateSettings(MAPPER.readTree(exchange.getRequestBody()))); return;
      }
      if ("GET".equals(exchange.getRequestMethod()) && "/api/files/content".equals(path)) {
        String requested = queryParameter(exchange.getRequestURI().getRawQuery(), "path");
        String sessionId = queryParameter(exchange.getRequestURI().getRawQuery(), "sessionId");
        json(exchange, MAPPER.createObjectNode().put("path", requested).put("content",
            WorkspaceFileService.readText(workspaceFor(sessionId), requested))); return;
      }
      if ("GET".equals(exchange.getRequestMethod()) && "/api/todos".equals(path)) {
        json(exchange, todos(queryParameter(exchange.getRequestURI().getRawQuery(), "sessionId"))); return;
      }
      if ("GET".equals(exchange.getRequestMethod()) && "/api/evaluation".equals(path)) {
        String query = exchange.getRequestURI().getRawQuery();
        json(exchange, evaluation("project".equals(queryParameter(query, "scope")), queryParameter(query, "sessionId"))); return;
      }
      if ("GET".equals(exchange.getRequestMethod()) && "/api/reflections".equals(path)) {
        String query = exchange.getRequestURI().getRawQuery();
        json(exchange, reflections("project".equals(queryParameter(query, "scope")), queryParameter(query, "sessionId"))); return;
      }
      if (path.startsWith("/api/todos/") && "POST".equals(exchange.getRequestMethod())) {
        String todoId = path.substring("/api/todos/".length());
        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        String operation = body.path("operation").asText("");
        String sessionId = body.path("sessionId").asText("");
        TodoStore store = new TodoStore(workspaceFor(sessionId));
        Object result = switch (operation) {
          case "complete" -> store.update(todoId, "completed", null);
          case "restore" -> store.update(todoId, "pending", null);
          case "delete" -> store.delete(todoId) ? Boolean.TRUE : null;
          default -> null;
        };
        if (result == null) { error(exchange, 404, "todo not found or operation invalid"); return; }
        publish("todo_changed", sessionId, MAPPER.createObjectNode().put("operation", operation).put("todoId", todoId));
        json(exchange, todos(sessionId)); return;
      }
      if ("GET".equals(exchange.getRequestMethod()) && "/api/events".equals(path)) { events(exchange); return; }
      if ("POST".equals(exchange.getRequestMethod()) && "/api/sessions".equals(path)) { json(exchange, createSession()); return; }
      if (path.startsWith("/api/sessions/") && path.indexOf('/', "/api/sessions/".length()) < 0
          && "DELETE".equals(exchange.getRequestMethod())) {
        String id = path.substring("/api/sessions/".length());
        boolean force = Boolean.parseBoolean(queryParameter(exchange.getRequestURI().getRawQuery(), "force"));
        try { json(exchange, deleteSession(id, force)); }
        catch (IllegalStateException conflict) { error(exchange, 409, conflict.getMessage()); }
        return;
      }
      if (path.startsWith("/api/sessions/") && path.endsWith("/fork") && "POST".equals(exchange.getRequestMethod())) {
        String parentId = path.substring("/api/sessions/".length(), path.length() - "/fork".length());
        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        json(exchange, forkSession(parentId, body.path("title").asText(""),
            body.path("isolated").asBoolean(false), body.path("baseRef").asText(""),
            body.path("branchName").asText(""))); return;
      }
      if (path.startsWith("/api/sessions/") && path.endsWith("/rename") && "POST".equals(exchange.getRequestMethod())) {
        String id = path.substring("/api/sessions/".length(), path.length() - "/rename".length());
        JsonNode body = MAPPER.readTree(exchange.getRequestBody()); json(exchange, renameSession(id, body.path("title").asText(""))); return;
      }
      if (path.startsWith("/api/sessions/") && path.endsWith("/transcript") && "GET".equals(exchange.getRequestMethod())) {
        String id = path.substring("/api/sessions/".length(), path.length() - "/transcript".length());
        json(exchange, transcript(id)); return;
      }
      if (path.startsWith("/api/sessions/") && path.endsWith("/files") && "GET".equals(exchange.getRequestMethod())) {
        String id = path.substring("/api/sessions/".length(), path.length() - "/files".length());
        json(exchange, files(id)); return;
      }
      if (path.startsWith("/api/sessions/") && path.endsWith("/trace") && "GET".equals(exchange.getRequestMethod())) {
        String id = path.substring("/api/sessions/".length(), path.length() - "/trace".length());
        trace(exchange, id); return;
      }
      if (path.startsWith("/api/sessions/") && path.endsWith("/messages") && "POST".equals(exchange.getRequestMethod())) {
        String id = path.substring("/api/sessions/".length(), path.length() - "/messages".length());
        JsonNode body = MAPPER.readTree(exchange.getRequestBody()); String content = body.path("content").asText("").trim();
        if (content.isBlank()) { error(exchange, 400, "content is required"); return; }
        Conversation target = conversation(id);
        if (target == null) { error(exchange, 404, "session not found"); return; }
        if (!workspaceAvailable(target)) {
          error(exchange, 409, "会话绑定的 Git Worktree 已不存在或未注册，请先恢复该 Worktree"); return;
        }
        if (!startTurn(id, content)) { error(exchange, 409, "Agent 正在执行当前任务，请等待结束后再发送"); return; }
        json(exchange, ok("accepted", id)); return;
      }
      error(exchange, 404, "not found");
    } catch (Exception e) {
      String message = e instanceof AccessDeniedException denied
          ? "会话目录不可写：" + denied.getFile() + "。请检查 .codeauto 目录权限或以正常用户权限重新启动 CodeAuto。"
          : (e.getMessage() == null ? e.toString() : e.getMessage());
      error(exchange, 500, message);
    }
  }

  private synchronized ObjectNode createSession() {
    Conversation draft = conversations.values().stream()
        .filter(this::isBlankRootDraft)
        .max(Comparator.comparing(conversation -> conversation.updatedAt))
        .orElse(null);
    if (draft != null) return state().put("active", draft.id);

    String id = UUID.randomUUID().toString().substring(0, 8); Conversation c = new Conversation(id); c.title = "新会话";
    c.messages.add(new ChatMessage.SystemMessage(systemPrompt(c.executionCwd)));
    conversations.put(id, c); publish("session_created", id, MAPPER.createObjectNode().put("sessionId", id)); return state().put("active", id);
  }

  private boolean isBlankRootDraft(Conversation conversation) {
    synchronized (conversation) {
      return conversation.parentSessionId == null
          && !conversation.running
          && conversation.turns == 0
          && conversation.messages.size() == 1
          && conversation.messages.getFirst() instanceof ChatMessage.SystemMessage;
    }
  }

  private ObjectNode settings() {
    RuntimeConfig current = runtime;
    return MAPPER.createObjectNode()
        .put("model", current.model()).put("baseUrl", current.baseUrl())
        .put("maxOutputTokens", current.maxOutputTokens()).put("maxRetries", current.maxRetries())
        .put("modelTimeoutSeconds", current.modelTimeoutSeconds()).put("contextWindow", current.contextWindow())
        .put("stripThinking", current.stripThinking()).put("authConfigured", !current.authToken().isBlank())
        .put("authHint", authHint(current.authToken()));
  }

  private ObjectNode updateSettings(JsonNode body) throws Exception {
    RuntimeConfig current = runtime;
    String token = body.path("clearAuthToken").asBoolean(false) ? ""
        : body.has("authToken") && !body.path("authToken").asText().isBlank()
            ? body.path("authToken").asText().trim() : current.authToken();
    RuntimeConfig updated = new RuntimeConfig(
        body.path("model").asText(current.model()).trim(),
        body.path("baseUrl").asText(current.baseUrl()).trim(), token,
        positive(body, "maxOutputTokens", current.maxOutputTokens()),
        nonNegative(body, "maxRetries", current.maxRetries()),
        positive(body, "modelTimeoutSeconds", current.modelTimeoutSeconds()),
        positive(body, "contextWindow", current.contextWindow()),
        body.has("stripThinking") ? body.path("stripThinking").asBoolean() : current.stripThinking());
    ConfigLoader.writeUserSettings(updated);
    runtime = updated;
    model = "mock".equalsIgnoreCase(updated.model()) ? new MockModelAdapter() : new AnthropicModelAdapter(updated, tools);
    return settings().put("saved", true);
  }

  private ArrayNode files(String id) {
    Conversation c = conversation(id);
    if (c == null) throw new IllegalArgumentException("session not found: " + id);
    requireWorkspace(c);
    ArrayNode out = MAPPER.createArrayNode();
    synchronized (c) {
      for (var file : WorkspaceFileService.collect(c.executionCwd, c.messages, c.trace)) {
        out.addObject().put("path", file.path()).put("operation", file.operation())
            .put("exists", file.exists()).put("size", file.size()).put("modifiedAt", file.modifiedAt());
      }
    }
    return out;
  }

  private Conversation conversation(String id) { return conversations.get(id); }

  private ObjectNode renameSession(String id, String requestedTitle) throws Exception {
    Conversation c = conversation(id);
    if (c == null) throw new IllegalArgumentException("session not found: " + id);
    String title = requestedTitle == null ? "" : requestedTitle.replaceAll("\\s+", " ").trim();
    if (title.isBlank()) throw new IllegalArgumentException("title is required");
    if (title.length() > 80) throw new IllegalArgumentException("title must be 80 characters or fewer");
    synchronized (c) { c.title = title; c.titleLocked = true; c.updatedAt = Instant.now(); }
    sessions.rename(id, title);
    publish("session_renamed", id, MAPPER.createObjectNode().put("title", title));
    return state().put("active", id);
  }

  private ObjectNode forkSession(String parentId, String requestedTitle, boolean isolated,
      String requestedBaseRef, String requestedBranch) throws Exception {
    Conversation parent = conversation(parentId); if (parent == null) throw new IllegalArgumentException("session not found: " + parentId);
    String id = UUID.randomUUID().toString().substring(0, 8); Conversation child = new Conversation(id);
    synchronized (parent) { child.messages = new ArrayList<>(parent.messages); child.savedCount = 1; child.parentSessionId = parentId; child.forkBoundary = parent.messages.size(); }
    child.title = requestedTitle == null || requestedTitle.isBlank() ? parent.title + " · 分支" : requestedTitle.replaceAll("\\s+", " ").trim();
    if (child.title.length() > 80) throw new IllegalArgumentException("title must be 80 characters or fewer");
    child.titleLocked = true;
    GitWorktreeService.WorktreeInfo createdWorktree = null;
    if (isolated) {
      String baseRef = requestedBaseRef == null || requestedBaseRef.isBlank()
          ? (parent.gitBranch == null || parent.gitBranch.isBlank() ? "HEAD" : parent.gitBranch)
          : requestedBaseRef;
      createdWorktree = worktrees.create(id, baseRef, requestedBranch);
      child.executionCwd = createdWorktree.path(); child.worktreePath = createdWorktree.path();
      child.gitBranch = createdWorktree.branch(); child.baseCommit = createdWorktree.head();
      child.messages.set(0, new ChatMessage.SystemMessage(systemPrompt(child.executionCwd)));
    }
    // The marker and copied transcript are persisted together. The system
    // prompt is reconstructed on load and is deliberately skipped here.
    try {
      sessions.createFork(id, parentId, child.forkBoundary, child.title, child.messages, 1,
          child.worktreePath == null ? null : child.worktreePath.toString(), child.gitBranch,
          child.baseCommit);
    } catch (Exception error) {
      if (createdWorktree != null) worktrees.rollbackCreated(createdWorktree);
      throw error;
    }
    conversations.put(id, child);
    publish("session_forked", id, MAPPER.createObjectNode().put("parentSessionId", parentId)
        .put("forkBoundary", child.forkBoundary).put("isolated", isolated)); return state().put("active", id);
  }

  private synchronized ObjectNode deleteSession(String id, boolean force) throws Exception {
    Conversation target = conversation(id);
    if (target == null) throw new IllegalArgumentException("session not found: " + id);
    if (target.running) throw new IllegalStateException("Agent 正在运行，无法删除当前会话");
    if (conversations.values().stream().anyMatch(item -> id.equals(item.parentSessionId))) {
      throw new IllegalStateException("该会话仍有子分支，请先删除最末端的子会话");
    }
    String parentId = target.parentSessionId;
    if (target.worktreePath != null && java.nio.file.Files.exists(target.worktreePath)) {
      worktrees.deleteManaged(target.worktreePath, force);
    } else if (target.worktreePath != null && target.gitBranch != null) {
      worktrees.deleteManagedBranch(target.gitBranch, force);
    }
    sessions.delete(id);
    conversations.remove(id);
    publish("session_deleted", id, MAPPER.createObjectNode().put("force", force));
    String next = parentId != null && conversations.containsKey(parentId) ? parentId
        : conversations.values().stream().max(Comparator.comparing(item -> item.updatedAt))
            .map(item -> item.id).orElse("");
    return state().put("active", next);
  }

  private boolean startTurn(String id, String content) {
    Conversation c = conversation(id); final int turnStartIndex; synchronized (c) { if (c.running) return false; turnStartIndex = c.messages.size(); c.running = true; c.turns++; c.messages.add(new ChatMessage.UserMessage(content)); if (c.turns == 1 && !c.titleLocked) { c.title = titleFor(content); try { sessions.rename(id, c.title); } catch (Exception ignored) {} } c.updatedAt = Instant.now(); }
    publish("user_message", id, MAPPER.createObjectNode().put("content", content));
    turns.submit(() -> {
      PermissionManager turnPermissions = permissions.rebind(c.executionCwd, permissionBroker.promptFor(id));
      turnPermissions.beginTurn();
      try {
        AgentLoopListener listener = listenerFor(id);
        AgentLoop loop = new AgentLoop(model, tools, new ToolContext(c.executionCwd, turnPermissions),
            128, listener, runtime.contextWindow());
        List<ChatMessage> result = loop.runTurn(c.messages);
        synchronized (c) { c.messages = new ArrayList<>(result); c.savedCount = persist(id, c); c.running = false; }
        ReflectionService.reflectIfNeeded(c.messages, model, c.executionCwd, turnStartIndex, id);
        publish("turn_complete", id, MAPPER.createObjectNode().put("messages", c.messages.size()));
      } catch (Exception e) { synchronized (c) { c.running = false; c.errors++; } ObjectNode error=MAPPER.createObjectNode().put("message", errorMessage(e)).put("type", e.getClass().getName()); if(e.getCause()!=null)error.put("cause", errorMessage(e.getCause())); publish("turn_error", id, error); }
      finally { turnPermissions.endTurn(); permissionBroker.endTurn(id); }
    });
    return true;
  }

  private AgentLoopListener listenerFor(String id) {
    return new AgentLoopListener() {
      public void onContextStats(ContextStats stats) { Conversation c=conversation(id); if(c!=null)c.contextTokens=stats.estimatedTokens(); publish("context_stats", id, MAPPER.createObjectNode().put("tokens", stats.estimatedTokens()).put("limit", runtime.contextWindow())); }
      public void onAssistantDelta(String delta) { publish("assistant_delta", id, MAPPER.createObjectNode().put("delta", delta)); }
      public void onThinkingDelta(String delta) { publish("thinking_delta", id, MAPPER.createObjectNode().put("delta", delta)); }
      public void onProgressMessage(String content) { publish("progress", id, MAPPER.createObjectNode().put("content", content).put("renderedHtml", MarkdownService.render(content))); }
      public void onAssistantMessage(String content) { publish("assistant_message", id, MAPPER.createObjectNode().put("content", content).put("renderedHtml", MarkdownService.render(content))); }
      public void onToolStart(String name, JsonNode input) { Conversation c=conversation(id); if(c!=null)c.toolCalls++; publish("tool_start", id, MAPPER.createObjectNode().put("name", name).set("input", input)); }
      public void onToolResult(String name, String output, boolean error) { if(error){Conversation c=conversation(id);if(c!=null)c.errors++;} publish("tool_result", id, MAPPER.createObjectNode().put("name", name).put("error", error).put("output", truncate(output, 12000))); }
      public void onAutoCompact(com.codeauto.context.CompactService.CompactResult result) { Conversation c=conversation(id);if(c!=null)c.compactions++; publish("compaction", id, MAPPER.createObjectNode().put("before", result.tokensBefore()).put("after", result.tokensAfter())); }
    };
  }

  private int persist(String id, Conversation c) { try { sessions.save(id, c.messages, c.savedCount); return c.messages.size(); } catch (Exception e) { return c.savedCount; } }

  private ObjectNode state() {
    ObjectNode out=MAPPER.createObjectNode(); out.put("workspace", cwd.toString()).put("contextWindow", runtime.contextWindow()).put("gitAvailable", worktrees.available()); ArrayNode list=out.putArray("sessions");
    Map<Path, GitWorktreeService.WorktreeInfo> currentWorktrees = new java.util.HashMap<>();
    for (var worktree : worktrees.list()) currentWorktrees.put(worktree.path(), worktree);
    ArrayNode worktreeItems = out.putArray("worktrees");
    currentWorktrees.values().stream().sorted(java.util.Comparator.comparing(GitWorktreeService.WorktreeInfo::current).reversed()
        .thenComparing(item -> item.branch() == null ? "" : item.branch()))
        .forEach(item -> worktreeItems.add(worktreeJson(item)));
    out.put("rootChangedFiles", currentWorktrees.containsKey(cwd)
        ? currentWorktrees.get(cwd).changedFiles() : 0);
    ArrayNode approvals = out.putArray("pendingApprovals");
    for (var approval : permissionBroker.pending()) approvals.add(permissionJson(approval));
    conversations.values().stream().sorted((a,b)->b.id.compareTo(a.id)).forEach(c -> list.add(sessionJson(c, currentWorktrees)));
    ObjectNode metrics=out.putObject("metrics"); int turns=0,toolsCount=0,errors=0,compactions=0,tokens=0; for(Conversation c:conversations.values()){turns+=c.turns;toolsCount+=c.toolCalls;errors+=c.errors;compactions+=c.compactions;tokens+=c.contextTokens;} metrics.put("turns",turns).put("toolCalls",toolsCount).put("errors",errors).put("compactions",compactions).put("contextTokens",tokens); return out;
  }

  private ObjectNode sessionJson(Conversation c, Map<Path, GitWorktreeService.WorktreeInfo> currentWorktrees) { synchronized(c){ ObjectNode n=MAPPER.createObjectNode().put("id",c.id).put("title",c.title).put("running",c.running).put("turns",c.turns).put("toolCalls",c.toolCalls).put("errors",c.errors).put("compactions",c.compactions).put("contextTokens",c.contextTokens).put("messageCount",c.messages.size()).put("updatedAt",c.updatedAt.toString()).put("executionCwd",c.executionCwd.toString()); if(c.parentSessionId!=null)n.put("parentSessionId",c.parentSessionId); if(c.forkBoundary!=null)n.put("forkBoundary",c.forkBoundary); if(c.worktreePath!=null){n.put("worktreePath",c.worktreePath.toString()).put("gitBranch",c.gitBranch==null?"":c.gitBranch).put("baseCommit",c.baseCommit==null?"":c.baseCommit);var info=currentWorktrees.get(c.worktreePath);n.put("worktreeAvailable",info!=null);if(info!=null)n.put("changedFiles",info.changedFiles()).put("head",info.head());} return n; } }

  private ObjectNode worktreeState() {
    ObjectNode out = MAPPER.createObjectNode().put("available", worktrees.available());
    ArrayNode items = out.putArray("worktrees");
    for (var item : worktrees.list()) items.add(worktreeJson(item));
    return out;
  }

  private ObjectNode worktreeGraphJson() {
    var graph = worktrees.graph(180); ObjectNode out = MAPPER.createObjectNode();
    ArrayNode nodes = out.putArray("nodes"); for (var node : graph.nodes()) {
      ObjectNode item = nodes.addObject().put("hash", node.hash()).put("subject", node.subject())
          .put("author", node.author()).put("timestamp", node.timestamp()); ArrayNode parents=item.putArray("parents"); node.parents().forEach(parents::add);
    }
    ArrayNode edges = out.putArray("edges"); for (var edge : graph.edges()) edges.addObject().put("child", edge.child()).put("parent", edge.parent());
    ObjectNode branches=out.putObject("branches"); graph.branches().forEach(branches::put);
    ObjectNode remoteBranches=out.putObject("remoteBranches"); graph.remoteBranches().forEach(remoteBranches::put); return out;
  }

  private void handleGit(HttpExchange exchange, String path) throws IOException {
    String remainder = path.substring("/api/sessions/".length());
    int marker = remainder.indexOf("/git/");
    if (marker <= 0) { error(exchange, 404, "not found"); return; }
    String sessionId = remainder.substring(0, marker);
    String operation = remainder.substring(marker + "/git/".length());
    Conversation conversation = conversation(sessionId);
    if (conversation == null) { error(exchange, 404, "session not found"); return; }
    try {
      Path worktree = workspaceFor(sessionId);
      if ("status".equals(operation) && "GET".equals(exchange.getRequestMethod())) {
        json(exchange, gitStatusJson(worktrees.status(worktree))); return;
      }
      if ("diff".equals(operation) && "GET".equals(exchange.getRequestMethod())) {
        String file = queryParameter(exchange.getRequestURI().getRawQuery(), "path");
        boolean staged = Boolean.parseBoolean(queryParameter(exchange.getRequestURI().getRawQuery(), "staged"));
        json(exchange, MAPPER.createObjectNode().put("path", file).put("staged", staged)
            .put("diff", worktrees.diff(worktree, file, staged))); return;
      }
      if ("POST".equals(exchange.getRequestMethod())) {
        if (conversation.running) throw new IllegalStateException("Agent 正在使用该工作区，请等待本轮结束后再修改 Git 状态");
        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        if ("stage".equals(operation) || "unstage".equals(operation)) {
          List<String> files = new ArrayList<>();
          if (body.path("paths").isArray()) for (JsonNode item : body.path("paths")) {
            if (item.isTextual()) files.add(item.asText());
          }
          boolean all = body.path("all").asBoolean(false);
          var status = "stage".equals(operation) ? worktrees.stage(worktree, files, all)
              : worktrees.unstage(worktree, files, all);
          publish("git_changed", sessionId, MAPPER.createObjectNode().put("operation", operation));
          json(exchange, gitStatusJson(status)); return;
        }
        if ("commit".equals(operation)) {
          var result = worktrees.commit(worktree, body.path("message").asText(""));
          publish("git_changed", sessionId, MAPPER.createObjectNode().put("operation", operation));
          json(exchange, gitResultJson(result)); return;
        }
        if ("push".equals(operation)) {
          var result = worktrees.push(worktree, body.path("remote").asText(""));
          publish("git_changed", sessionId, MAPPER.createObjectNode().put("operation", operation));
          json(exchange, gitResultJson(result)); return;
        }
      }
      error(exchange, 404, "unknown git operation");
    } catch (IllegalArgumentException invalid) {
      error(exchange, 400, invalid.getMessage());
    } catch (IllegalStateException conflict) {
      error(exchange, 409, conflict.getMessage());
    }
  }

  private static ObjectNode gitResultJson(GitWorktreeService.GitOperationResult result) {
    ObjectNode node = gitStatusJson(result.status());
    node.put("ok", true).put("message", result.message());
    return node;
  }

  private static ObjectNode gitStatusJson(GitWorktreeService.GitStatus status) {
    ObjectNode node = MAPPER.createObjectNode().put("path", status.path().toString())
        .put("branch", status.branch()).put("detached", status.detached())
        .put("upstream", status.upstream()).put("remote", status.remote())
        .put("ahead", status.ahead()).put("behind", status.behind());
    ArrayNode remotes = node.putArray("remotes");
    for (String remote : status.remotes()) remotes.add(remote);
    ArrayNode files = node.putArray("files");
    for (var file : status.files()) files.addObject().put("path", file.path())
        .put("indexStatus", file.indexStatus()).put("worktreeStatus", file.worktreeStatus())
        .put("staged", file.staged()).put("unstaged", file.unstaged())
        .put("untracked", file.untracked()).put("conflict", file.conflict());
    return node;
  }

  private static ObjectNode worktreeJson(GitWorktreeService.WorktreeInfo item) {
    ObjectNode node = MAPPER.createObjectNode().put("path", item.path().toString())
        .put("head", item.head()).put("branch", item.branch() == null ? "" : item.branch())
        .put("detached", item.detached()).put("locked", item.locked()).put("bare", item.bare())
        .put("changedFiles", item.changedFiles()).put("current", item.current()).put("managed", item.managed())
        .put("commitSubject", item.commitSubject()).put("commitAuthor", item.commitAuthor());
    ArrayNode commits = node.putArray("recentCommits");
    for (var commit : item.recentCommits()) commits.addObject().put("hash", commit.hash())
        .put("subject", commit.subject()).put("author", commit.author());
    return node;
  }

  private static ObjectNode permissionJson(WebPermissionBroker.PendingApproval approval) {
    ObjectNode node = MAPPER.createObjectNode().put("id", approval.id())
        .put("sessionId", approval.sessionId()).put("kind", approval.request().kind())
        .put("summary", approval.request().summary()).put("scope", approval.request().scope())
        .put("createdAt", approval.createdAt().toString());
    ArrayNode choices = node.putArray("choices");
    for (PermissionDecision choice : approval.request().choices()) choices.add(choice.name());
    return node;
  }

  private ArrayNode transcript(String id) throws Exception {
    ArrayNode out = MAPPER.createArrayNode(); Conversation c = conversation(id);
    if (c != null) {
      synchronized (c) { for (ChatMessage message : c.messages) out.add(messageJson(message)); }
    } else {
      for (var entry : sessions.loadTranscript(id)) {
        ObjectNode node = MAPPER.valueToTree(entry);
        if (("assistant".equals(entry.kind()) || "progress".equals(entry.kind())) && entry.body() != null) {
          node.put("renderedHtml", MarkdownService.render(entry.body()));
        }
        out.add(node);
      }
    }
    return out;
  }

  private static ObjectNode messageJson(ChatMessage message) {
    ObjectNode node = MAPPER.valueToTree(message);
    String markdown = switch (message) {
      case ChatMessage.AssistantMessage value -> value.content();
      case ChatMessage.AssistantProgressMessage value -> value.content();
      case ChatMessage.ContextSummaryMessage value -> value.content();
      case ChatMessage.AssistantRawMessage value -> rawAssistantText(value.content());
      default -> null;
    };
    if (markdown != null && !markdown.isBlank()) node.put("renderedHtml", MarkdownService.render(markdown));
    return node;
  }

  private static String rawAssistantText(JsonNode content) {
    if (content == null || !content.isArray()) return "";
    StringBuilder text = new StringBuilder();
    for (JsonNode block : content) {
      if ("text".equals(block.path("type").asText()) && !block.path("text").asText("").isBlank()) {
        if (!text.isEmpty()) text.append('\n');
        text.append(block.path("text").asText());
      }
    }
    return text.toString();
  }

  private ObjectNode evaluation(boolean projectScope, String sessionId) {
    ObjectNode out = MAPPER.createObjectNode().put("scope", projectScope ? "project" : "session");
    ArrayNode series = out.putArray("contextSeries");
    ArrayNode tokenSeries = out.putArray("tokenSeries");
    ArrayNode errorSeries = out.putArray("toolSuccessSeries");
    int turns = 0, tools = 0, errors = 0, compactions = 0, toolErrors = 0;
    int inputTokens = 0, outputTokens = 0, totalTokens = 0, cacheRead = 0, cacheCreation = 0, experienceHits = 0, experienceCandidates = 0, contextTokens = 0;
    List<Conversation> selected = new ArrayList<>();
    if (projectScope) selected.addAll(conversations.values());
    else { Conversation c = conversation(sessionId); if (c != null) selected.add(c); }
    for (Conversation c : selected) synchronized (c) {
      turns += c.turns; tools += c.toolCalls; errors += c.errors; compactions += c.compactions;
      List<ProviderUsage> usages = new ArrayList<>();
      boolean afterToolError = false, hitForError = false;
      for (ChatMessage message : c.messages) {
        ProviderUsage usage = usageOf(message);
        if (usage != null) usages.add(usage);
        String text = messageText(message);
        if (message instanceof ChatMessage.ToolResultMessage result && result.isError()) { experienceCandidates++; afterToolError = true; hitForError = false; }
        if (afterToolError && (text.contains("[bullet:") || text.contains(".codeauto/reflections") || text.contains(".codeauto/bullets"))) { if (!hitForError) experienceHits++; hitForError = true; }
      }
      for (ProviderUsage usage : usages) {
        inputTokens += usage.inputTokens(); outputTokens += usage.outputTokens(); totalTokens += usage.totalTokens();
        cacheRead += usage.cacheReadInputTokens(); cacheCreation += usage.cacheCreationInputTokens();
      }
      int traceTools = 0, traceToolErrors = 0;
      int runningTools = 0, runningToolErrors = 0, lastContextTokens = 0;
      for (JsonNode event : c.trace) {
        if ("tool_start".equals(event.path("type").asText())) { traceTools++; runningTools++; }
        if ("tool_result".equals(event.path("type").asText())) {
          if (event.path("payload").path("error").asBoolean(false)) { traceToolErrors++; runningToolErrors++; }
          ObjectNode point = errorSeries.addObject().put("time", event.path("time").asText(""));
          point.put("rate", runningTools == 0 ? 1.0 : 1.0 - (double) runningToolErrors / runningTools);
        }
        if ("context_stats".equals(event.path("type").asText())) {
          lastContextTokens = event.path("payload").path("tokens").asInt(0);
          ObjectNode point = series.addObject().put("tokens", lastContextTokens);
          point.put("time", event.path("time").asText("")).put("sessionId", c.id);
        }
      }
      int seriesBefore = tokenSeries.size(), usageIndex = 0;
      for (JsonNode context : c.trace) if ("context_stats".equals(context.path("type").asText())) {
        ObjectNode point = tokenSeries.addObject().put("time", context.path("time").asText(""));
        point.put("contextTokens", context.path("payload").path("tokens").asInt(0));
        if (usageIndex < usages.size()) { ProviderUsage usage = usages.get(usageIndex++); point.put("inputTokens", usage.inputTokens()).put("outputTokens", usage.outputTokens()).put("totalTokens", usage.totalTokens()).put("cacheReadTokens", usage.cacheReadInputTokens()).put("cacheCreationTokens", usage.cacheCreationInputTokens()); }
        else point.put("inputTokens", 0).put("outputTokens", 0).put("totalTokens", 0).put("cacheReadTokens", 0).put("cacheCreationTokens", 0);
      }
      if (lastContextTokens == 0 && !usages.isEmpty()) lastContextTokens = usages.get(usages.size() - 1).inputTokens();
      if (tokenSeries.size() == seriesBefore && !usages.isEmpty()) {
        for (ProviderUsage usage : usages) tokenSeries.addObject().put("time", c.updatedAt.toString())
            .put("contextTokens", usage.inputTokens()).put("inputTokens", usage.inputTokens())
            .put("outputTokens", usage.outputTokens()).put("totalTokens", usage.totalTokens())
            .put("cacheReadTokens", usage.cacheReadInputTokens()).put("cacheCreationTokens", usage.cacheCreationInputTokens());
      }
      contextTokens += lastContextTokens;
      if (traceTools > 0 || c.toolCalls == 0) { tools += traceTools - c.toolCalls; toolErrors += traceToolErrors; }
      else {
        for (ChatMessage message : c.messages) {
          if (message instanceof ChatMessage.ToolResultMessage result && result.isError()) toolErrors++;
        }
      }
    }
    out.putObject("metrics").put("turns", turns).put("toolCalls", tools).put("errors", errors)
        .put("toolErrors", toolErrors)
        .put("toolErrorRate", tools == 0 ? 0.0 : (double) toolErrors / tools)
        .put("toolSuccessRate", tools == 0 ? 0.0 : 1.0 - (double) toolErrors / tools)
        .put("inputTokens", inputTokens).put("outputTokens", outputTokens).put("totalTokens", totalTokens)
        .put("cacheReadTokens", cacheRead).put("cacheCreationTokens", cacheCreation)
        .put("cacheHitRate", inputTokens == 0 ? 0.0 : (double) cacheRead / inputTokens)
        .put("experienceHits", experienceHits)
        .put("experienceHitRate", experienceCandidates == 0 ? 0.0 : Math.min(1.0, (double) experienceHits / experienceCandidates))
        .put("compactions", compactions).put("contextTokens", contextTokens);
    if (errorSeries.isEmpty() && tools > 0) errorSeries.addObject().put("time", Instant.now().toString()).put("rate", 1.0 - (double) toolErrors / tools);
    out.put("seriesAvailable", series.size() > 0);
    return out;
  }

  private static ProviderUsage usageOf(ChatMessage message) {
    if (message instanceof ChatMessage.AssistantMessage m) return m.providerUsage();
    if (message instanceof ChatMessage.AssistantRawMessage m) return m.providerUsage();
    if (message instanceof ChatMessage.AssistantProgressMessage m) return m.providerUsage();
    if (message instanceof ChatMessage.AssistantToolCallMessage m) return m.providerUsage();
    return null;
  }

  private static String messageText(ChatMessage message) {
    if (message instanceof ChatMessage.SystemMessage m) return m.content();
    if (message instanceof ChatMessage.UserMessage m) return m.content();
    if (message instanceof ChatMessage.AssistantMessage m) return m.content();
    if (message instanceof ChatMessage.AssistantProgressMessage m) return m.content();
    return "";
  }

  private ObjectNode reflections(boolean projectScope, String sessionId) {
    Path workspace = projectScope ? cwd : workspaceFor(sessionId);
    Path root = workspace.resolve(".codeauto");
    ObjectNode out = MAPPER.createObjectNode().put("scope", projectScope ? "project" : "session")
        .put("storage", root.toString());
    ArrayNode reflectionItems = out.putArray("reflections");
    ArrayNode bulletItems = out.putArray("bullets");
    Path reflectionsRoot = projectScope ? root.resolve("reflections") : root.resolve("reflections").resolve("sessions").resolve(sessionId == null ? "" : sessionId);
    Path bulletsRoot = projectScope ? root.resolve("bullets") : root.resolve("bullets").resolve("sessions").resolve(sessionId == null ? "" : sessionId);
    List<com.codeauto.memory.MemoryEntry> bullets = new com.codeauto.memory.MemoryManager(bulletsRoot).list();
    for (var entry : new com.codeauto.memory.MemoryManager(reflectionsRoot).list()) {
      ObjectNode reflection = memoryJson(entry);
      bullets.stream().max(Comparator.comparingInt(bullet -> memoryPairScore(entry, bullet)))
          .filter(bullet -> memoryPairScore(entry, bullet) > 0)
          .ifPresent(bullet -> reflection.set("pairedBullet", memoryJson(bullet)));
      reflectionItems.add(reflection);
    }
    for (var entry : bullets) bulletItems.add(memoryJson(entry));
    if (!projectScope && reflectionItems.isEmpty() && bulletItems.isEmpty()) {
      out.put("scopeNote", "当前项目已有记录，但当前会话尚未生成独立反思/Bullet；未混入项目记录");
    }
    return out;
  }

  private static ObjectNode memoryJson(com.codeauto.memory.MemoryEntry entry) {
    return MAPPER.createObjectNode().put("id", entry.id()).put("title", entry.title())
        .put("content", entry.content()).put("renderedHtml", MarkdownService.render(entry.content()))
        .put("project", entry.project()).put("section", entry.section())
        .put("bulletId", entry.bulletId()).put("updatedAt", entry.updatedAt().toString());
  }

  private static int memoryPairScore(com.codeauto.memory.MemoryEntry reflection, com.codeauto.memory.MemoryEntry bullet) {
    String content = reflection.content() == null ? "" : reflection.content().toLowerCase();
    String lesson = bullet.content() == null ? "" : bullet.content().toLowerCase();
    if (!lesson.isBlank() && content.contains(lesson.substring(0, Math.min(60, lesson.length())))) return 100;
    int score = 0;
    for (String word : bullet.title().toLowerCase().split("[^a-z0-9]+")) {
      if (word.length() >= 4 && content.contains(word)) score++;
    }
    return score;
  }

  private ObjectNode todos(String sessionId) {
    ObjectNode out = MAPPER.createObjectNode(); ArrayNode groups = out.putArray("groups");
    for (TodoStore.TodoGroup group : new TodoStore(workspaceFor(sessionId)).groups()) {
      ObjectNode g = groups.addObject().put("id", group.id()).put("title", group.title())
          .put("pending", group.pendingCount()).put("inProgress", group.inProgressCount()).put("completed", group.completedCount());
      ArrayNode entries = g.putArray("entries");
      for (var item : group.entries()) entries.add(MAPPER.valueToTree(item));
    }
    return out;
  }

  private Path workspaceFor(String sessionId) {
    Conversation conversation = sessionId == null || sessionId.isBlank() ? null : conversation(sessionId);
    if (conversation == null) return cwd;
    requireWorkspace(conversation);
    return conversation.executionCwd;
  }

  private void requireWorkspace(Conversation conversation) {
    if (!workspaceAvailable(conversation)) {
      throw new IllegalStateException("会话绑定的 Git Worktree 已不存在或未注册");
    }
  }

  private boolean workspaceAvailable(Conversation conversation) {
    if (conversation.worktreePath == null) return true;
    boolean available = java.nio.file.Files.isDirectory(conversation.worktreePath)
        && worktrees.isRegistered(conversation.worktreePath);
    conversation.worktreeUnavailable = !available;
    return available;
  }

  private static String systemPrompt(Path workspace) {
    return "You are CodeAuto, a careful coding assistant. Workspace: " + workspace;
  }

  private void trace(HttpExchange exchange, String id) throws IOException {
    Conversation c = conversation(id); ObjectNode out = MAPPER.createObjectNode().put("sessionId", id);
    ArrayNode events = out.putArray("events");
    if (c != null) { synchronized (c) { for (JsonNode event : c.trace) events.add(event); } }
    byte[] bytes = (out.toPrettyString() + "\n").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"codeauto-trace-" + id + ".json\"");
    exchange.sendResponseHeaders(200, bytes.length); try (OutputStream outStream = exchange.getResponseBody()) { outStream.write(bytes); }
  }

  private void events(HttpExchange exchange) throws IOException { exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8"); exchange.getResponseHeaders().set("Cache-Control", "no-cache"); exchange.getResponseHeaders().set("Connection", "keep-alive"); exchange.sendResponseHeaders(200, 0); OutputStream out=exchange.getResponseBody(); subscribers.put(out, new Object()); try { out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); while(true){ Thread.sleep(15000); out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); } } catch(Exception ignored){} finally { subscribers.remove(out); try{out.close();}catch(Exception ignored){} } }

  private void publish(String type, String sessionId, JsonNode payload) { ObjectNode e=MAPPER.createObjectNode().put("eventId",UUID.randomUUID().toString()).put("time",Instant.now().toString()).put("type",type).put("sessionId",sessionId).set("payload",payload); Conversation c=conversation(sessionId); if(c!=null){synchronized(c){c.trace.add(e.deepCopy());}} persistEvaluationEvent(e); byte[] bytes=("event: agent_event\ndata: "+e.toString()+"\n\n").getBytes(StandardCharsets.UTF_8); for(OutputStream out:subscribers.keySet()){ try{synchronized(out){out.write(bytes);out.flush();}}catch(Exception ex){subscribers.remove(out);}} }

  private Path evaluationPath(String sessionId) { return cwd.resolve(".codeauto").resolve("evaluation").resolve("sessions").resolve(sessionId + ".jsonl"); }

  private List<JsonNode> loadEvaluationTrace(String sessionId) {
    Path file = evaluationPath(sessionId); if (!Files.isRegularFile(file)) return List.of();
    try { List<JsonNode> result = new ArrayList<>(); for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!line.isBlank()) result.add(MAPPER.readTree(line)); return result; }
    catch (Exception ignored) { return List.of(); }
  }

  private void persistEvaluationEvent(JsonNode event) {
    try { Path file = evaluationPath(event.path("sessionId").asText()); Files.createDirectories(file.getParent()); Files.writeString(file, event.toString() + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
    catch (Exception ignored) { }
  }

  private static String errorMessage(Throwable error) {
    if (error == null) return "未知错误";
    String message = error.getMessage();
    return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
  }
  private static String truncate(String s,int n){ if(s==null)return ""; return s.length()<=n?s:s.substring(0,n)+"\n…[truncated]"; }
  private static int positive(JsonNode body, String field, int fallback) {
    int value = body.path(field).asInt(fallback);
    if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    return value;
  }
  private static int nonNegative(JsonNode body, String field, int fallback) {
    int value = body.path(field).asInt(fallback);
    if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
    return value;
  }
  private static String authHint(String token) {
    if (token == null || token.isBlank()) return "";
    return token.length() <= 4 ? "••••" : "••••" + token.substring(token.length() - 4);
  }
  private static String queryParameter(String rawQuery, String name) {
    if (rawQuery == null) return "";
    for (String pair : rawQuery.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      if (name.equals(key)) return parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
    }
    return "";
  }
  private static String titleFor(String content) { String oneLine = content.replaceAll("\\s+", " ").trim(); return oneLine.length() <= 28 ? oneLine : oneLine.substring(0, 28) + "…"; }
  private static ObjectNode ok(String message,String id){return MAPPER.createObjectNode().put("ok",true).put("message",message).put("sessionId",id);}
  private static void resource(HttpExchange e,String name,String type)throws IOException{var in=CodeAutoWebServer.class.getClassLoader().getResourceAsStream(name);if(in==null){error(e,404,"resource not found");return;}byte[] b=in.readAllBytes();e.getResponseHeaders().set("Content-Type",type);e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(200,b.length);try(OutputStream o=e.getResponseBody()){o.write(b);}}
  private static void json(HttpExchange e,JsonNode n)throws IOException{byte[] b=n.toString().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.sendResponseHeaders(200,b.length);try(OutputStream o=e.getResponseBody()){o.write(b);}}
  private static void error(HttpExchange e,int status,String message)throws IOException{ObjectNode n=MAPPER.createObjectNode().put("ok",false).put("error",message==null?"error":message);byte[] b=n.toString().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.sendResponseHeaders(status,b.length);try(OutputStream o=e.getResponseBody()){o.write(b);}}
  @Override public synchronized void close(){if(server!=null)server.stop(0);permissionBroker.close();turns.shutdownNow();for(OutputStream o:subscribers.keySet()){try{o.close();}catch(Exception ignored){}}subscribers.clear();}
  private final class Conversation { final String id; String title = "会话"; String parentSessionId; Integer forkBoundary; Path executionCwd=cwd; Path worktreePath; String gitBranch; String baseCommit; List<ChatMessage> messages=new ArrayList<>(); List<JsonNode> trace=new ArrayList<>(); int savedCount=1,turns,toolCalls,errors,compactions,contextTokens; boolean running,titleLocked,worktreeUnavailable; Instant updatedAt=Instant.now(); Conversation(String id){this.id=id;} }
}
