package com.codeauto.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.skills.SkillService;
import com.codeauto.todo.TodoStore;
import com.codeauto.tool.ToolContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Slash command dispatch and local tool shortcuts.
 * Calls back into {@link TuiApp} via package-private methods.
 */
final class TuiCommands {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TuiCommands() {}

  record SlashCommand(String usage, String description) {}

  static final List<SlashCommand> SLASH_COMMANDS = List.of(
      new SlashCommand("/help", "Show commands"),
      new SlashCommand("/tools", "List available tools"),
      new SlashCommand("/skills", "List discovered skills"),
      new SlashCommand("/sessions", "List saved sessions"),
      new SlashCommand("/status", "Show workspace, session, and context stats"),
      new SlashCommand("/model", "Show or switch active model"),
      new SlashCommand("/mcp", "Show MCP server and tool status"),
      new SlashCommand("/ls [path]", "List local files without model call"),
      new SlashCommand("/grep <pattern>::[path]", "Search local files without model call"),
      new SlashCommand("/read <path>", "Read local file without model call"),
      new SlashCommand("/write <path>::<content>", "Write local file with review"),
      new SlashCommand("/modify <path>::<content>", "Replace local file with review"),
      new SlashCommand("/edit <path>::<search>::<replace>", "Edit local file with review"),
      new SlashCommand("/patch <path>::<search>::<replace>...", "Batch replace local file with review"),
      new SlashCommand("/cmd <command>", "Run local command without model call"),
      new SlashCommand("/memory", "List, add, or delete persistent memories"),
      new SlashCommand("/todo", "List, add, update, or delete todo tasks"),
      new SlashCommand("/new", "Start a new session"),
      new SlashCommand("/resume", "Open saved session picker"),
      new SlashCommand("/fork", "Save current transcript into a new session"),
      new SlashCommand("/rename <name>", "Rename current session metadata"),
      new SlashCommand("/compact", "Compact middle conversation messages"),
      new SlashCommand("/config-paths", "Show config home directory"),
      new SlashCommand("/permissions", "Show permission storage and rule counts"),
      new SlashCommand("/exit", "Exit")
  );

  /**
   * Execute a slash command or local shortcut.
   * Returns true if the command was handled (caller should render()).
   * Returns false if the text should be submitted to the model.
   */
  static boolean execute(String text, TuiApp app) {
    if (text.equals("/exit")) {
      app.setRunning(false);
      return true;
    }
    if (text.equals("/help")) { app.addEntry(assistant(app.nextEntryId(), helpText())); return true; }
    if (text.equals("/tools")) { app.addEntry(assistant(app.nextEntryId(), toolsText(app))); return true; }
    if (text.equals("/skills")) { app.addEntry(assistant(app.nextEntryId(), skillsText(app))); return true; }
    if (text.equals("/sessions")) { app.addEntry(assistant(app.nextEntryId(), sessionsText(app))); return true; }
    if (text.equals("/status")) { app.addEntry(assistant(app.nextEntryId(), statusText(app))); return true; }
    if (text.startsWith("/mcp")) { handleMcp(text, app); return true; }
    if (text.equals("/model")) { app.addEntry(assistant(app.nextEntryId(), app.modelName())); return true; }
    if (text.startsWith("/model ")) { app.switchModel(text.substring("/model ".length()).trim()); return true; }
    if (tryLocalShortcut(text, app)) return true;
    if (text.equals("/memory") || text.startsWith("/memory ")) {
      app.addEntry(assistant(app.nextEntryId(), runMemoryCommand(text, app))); return true;
    }
    if (text.equals("/todo") || text.startsWith("/todo ")) {
      app.addEntry(assistant(app.nextEntryId(), runTodoCommand(text, app.cwd()))); return true;
    }
    if (text.equals("/new")) { app.newSession(); return true; }
    if (text.equals("/fork")) { app.forkSession(); return true; }
    if (text.startsWith("/resume")) { handleResume(text, app); return true; }
    if (text.startsWith("/rename ")) { app.renameSession(text.substring("/rename ".length()).trim()); return true; }
    if (text.equals("/compact")) { app.runCompact(); return true; }
    if (text.equals("/config-paths")) { app.addEntry(assistant(app.nextEntryId(), configPathsText())); return true; }
    if (text.equals("/permissions")) { app.addEntry(assistant(app.nextEntryId(), app.permissionsSummary())); return true; }
    return false;
  }

  // --- Pure command handlers ---

  private static String helpText() {
    return """
        /help       Show commands
        /tools      List available tools
        /skills     List discovered skills
        /sessions   List saved sessions
        /status     Show workspace, session, and context stats
        /model      Show active model name
        /model <n>  Switch model and persist to user settings
        /mcp        Show MCP server and tool status
        /ls [path]  List local files without a model call
        /grep <pattern>::[path] Search local files
        /read <path> Read a local file
        /write <path>::<content> Write a local file with review
        /modify <path>::<content> Replace a local file with review
        /edit <path>::<search>::<replace> Edit a local file with review
        /patch <path>::<search>::<replace>... Batch replace a local file
        /cmd <command> Run a local command
        /memory list [query] List persistent memories
        /memory add <type>::<title>::<content> Save a memory
        /memory delete <id> Delete a memory
        /todo       List tasks, or add/done/undo/delete/clear
        /new        Start a new session
        /resume    Open saved session picker
        /resume <id> Load a saved session by id
        /fork       Save current transcript into a new session
        /rename <n> Rename current session metadata
        /compact    Compact middle conversation messages
        /config-paths Show config home directory
        /permissions Show permission storage and rule counts
        /exit       Exit""";
  }

  private static String toolsText(TuiApp app) {
    var sb = new StringBuilder();
    for (var t : app.tools().list()) {
      sb.append(t.name()).append(": ").append(t.description()).append("\n");
    }
    return sb.toString().trim();
  }

  private static String skillsText(TuiApp app) {
    var skills = new SkillService(app.cwd()).discover();
    if (skills.isEmpty()) return "(none)";
    var sb = new StringBuilder();
    skills.forEach(s -> sb.append(s.name()).append(": ").append(s.skillFile()).append("\n"));
    return sb.toString().trim();
  }

  private static String sessionsText(TuiApp app) {
    try {
      var summaries = app.sessions().list();
      if (summaries.isEmpty()) return "(none)";
      var sb = new StringBuilder();
      summaries.forEach(s ->
          sb.append(s.id()).append("  ").append(s.title()).append("  ").append(s.updatedAt()).append("\n"));
      return sb.toString().trim();
    } catch (Throwable e) {
      return "Error: " + e.getMessage();
    }
  }

  private static String statusText(TuiApp app) {
    var stats = app.contextStats();
    return "workspace=" + app.cwd()
        + "\nsession=" + app.sessionId()
        + "\ntools=" + app.tools().list().size()
        + "\nskills=" + (app.skillCount() >= 0 ? app.skillCount() : "?")
        + "\nmcp=" + (app.mcpToolCount() >= 0 ? app.mcpToolCount() : "?")
        + "\nctx=" + (stats != null ? stats.estimatedTokens() + " est tokens, level=" + stats.warningLevel() : "?");
  }

  private static void handleMcp(String text, TuiApp app) {
    if (!text.equals("/mcp")) {
      app.addEntry(assistant(app.nextEntryId(), "Usage: /mcp"));
    } else {
      app.addEntry(assistant(app.nextEntryId(), app.mcpStatus()));
    }
  }

  private static void handleResume(String text, TuiApp app) {
    if (text.equals("/resume")) {
      app.openSessionPicker();
    } else {
      app.resumeSessionById(text.substring("/resume ".length()).trim());
    }
  }

  private static String configPathsText() {
    return "home=" + com.codeauto.config.RuntimeConfig.homeDir();
  }

  // --- Local tool shortcuts ---

  static boolean tryLocalShortcut(String text, TuiApp app) {
    ObjectNode input = MAPPER.createObjectNode();
    String toolName = null;
    if (text.equals("/ls") || text.startsWith("/ls ")) {
      toolName = "list_files";
      String path = text.length() > 3 ? text.substring(3).trim() : ".";
      input.put("path", path.isBlank() ? "." : path);
    } else if (text.startsWith("/read ")) {
      toolName = "read_file";
      input.put("path", text.substring("/read ".length()).trim());
    } else if (text.startsWith("/grep ")) {
      toolName = "grep_files";
      String[] parts = splitShortcutPayload(text.substring("/grep ".length()).trim(), 2);
      input.put("pattern", parts[0]);
      input.put("path", parts.length > 1 && !parts[1].isBlank() ? parts[1] : ".");
    } else if (text.startsWith("/write ")) {
      toolName = "write_file";
      String[] parts = splitShortcutPayload(text.substring("/write ".length()).trim(), 2);
      if (parts.length < 2) { app.addEntry(assistant(app.nextEntryId(), "Usage: /write <path>::<content>")); return true; }
      input.put("path", parts[0]);
      input.put("content", parts[1]);
    } else if (text.startsWith("/modify ")) {
      toolName = "modify_file";
      String[] parts = splitShortcutPayload(text.substring("/modify ".length()).trim(), 2);
      if (parts.length < 2) { app.addEntry(assistant(app.nextEntryId(), "Usage: /modify <path>::<content>")); return true; }
      input.put("path", parts[0]);
      input.put("content", parts[1]);
    } else if (text.startsWith("/edit ")) {
      toolName = "edit_file";
      String[] parts = splitShortcutPayload(text.substring("/edit ".length()).trim(), 3);
      if (parts.length < 3) { app.addEntry(assistant(app.nextEntryId(), "Usage: /edit <path>::<search>::<replace>")); return true; }
      input.put("path", parts[0]);
      input.put("oldText", parts[1]);
      input.put("newText", parts[2]);
    } else if (text.startsWith("/patch ")) {
      return runPatchShortcut(text.substring("/patch ".length()).trim(), app);
    } else if (text.startsWith("/cmd ")) {
      toolName = "run_command";
      input.put("command", parseCmdShortcut(text.substring("/cmd ".length()).trim(), app.cwd()));
    }
    if (toolName == null) return false;
    runShortcutTool(toolName, input, app);
    return true;
  }

  private static boolean runPatchShortcut(String payload, TuiApp app) {
    String[] parts = splitShortcutPayload(payload, 0);
    if (parts.length < 3 || parts.length % 2 == 0) {
      app.addEntry(assistant(app.nextEntryId(), "Usage: /patch <path>::<search>::<replace>[::<search>::<replace>...]"));
      return true;
    }
    try {
      Path file = app.cwd().resolve(parts[0]).normalize();
      String before = java.nio.file.Files.readString(file);
      String after = before;
      for (int i = 1; i < parts.length; i += 2) {
        after = after.replace(parts[i], parts[i + 1]);
      }
      ObjectNode input = MAPPER.createObjectNode()
          .put("path", parts[0])
          .put("content", after);
      runShortcutTool("modify_file", input, app);
    } catch (Exception error) {
      app.addEntry(new TranscriptEntry.Tool(app.nextEntryId(), "patch",
          TranscriptEntry.ToolStatus.ERROR, error.getMessage()));
    }
    return true;
  }

  static String runTodoCommand(String text, Path cwd) {
    TodoStore store = new TodoStore(cwd);
    String rest = text.equals("/todo") ? "list" : text.substring("/todo ".length()).trim();

    if (rest.equals("list")) {
      var todos = store.list(null);
      if (todos.isEmpty()) return "(no todos)";
      StringBuilder out = new StringBuilder();
      int pending = 0, inProgress = 0, completed = 0;
      for (var t : todos) {
        String icon = switch (t.status()) {
          case "completed" -> { completed++; yield "[x]"; }
          case "in_progress" -> { inProgress++; yield "[>]"; }
          default -> { pending++; yield "[ ]"; }
        };
        out.append(icon).append(" ").append(t.id()).append(": ").append(t.content()).append("\n");
      }
      out.append("--- ").append(todos.size()).append(" total (")
          .append(pending).append(" pending, ")
          .append(inProgress).append(" in progress, ")
          .append(completed).append(" completed)");
      return out.toString();
    }
    if (rest.startsWith("add ")) {
      String content = rest.substring("add ".length()).trim();
      if (content.isBlank()) return "Usage: /todo add <content>";
      var entry = store.add(content, content);
      return "Added todo " + entry.id() + ": " + entry.content();
    }
    if (rest.startsWith("done ")) {
      String id = rest.substring("done ".length()).trim();
      var updated = store.update(id, "completed", null);
      if (updated == null) return "Todo not found: " + id;
      return "Completed todo " + id + ": " + updated.content();
    }
    if (rest.startsWith("undo ")) {
      String id = rest.substring("undo ".length()).trim();
      var updated = store.update(id, "pending", null);
      if (updated == null) return "Todo not found: " + id;
      return "Reset todo " + id + " to pending: " + updated.content();
    }
    if (rest.startsWith("delete ")) {
      String id = rest.substring("delete ".length()).trim();
      return store.delete(id) ? "Deleted todo " + id : "Todo not found: " + id;
    }
    if (rest.equals("clear")) {
      int removed = store.clearCompleted();
      return "Cleared " + removed + " completed todo(s)";
    }
    return "Usage: /todo [list] | /todo add <content> | /todo done <id> | /todo undo <id> | /todo delete <id> | /todo clear";
  }

  static String runMemoryCommand(String text, TuiApp app) {
    String rest = text.equals("/memory") ? "list" : text.substring("/memory ".length()).trim();
    String toolName;
    ObjectNode input = MAPPER.createObjectNode();
    if (rest.equals("list") || rest.startsWith("list ")) {
      toolName = "list_memory";
      String query = rest.length() > 4 ? rest.substring(4).trim() : "";
      if (!query.isBlank()) input.put("query", query);
    } else if (rest.startsWith("add ")) {
      toolName = "save_memory";
      String[] parts = splitShortcutPayload(rest.substring("add ".length()).trim(), 3);
      if (parts.length < 3) return "Usage: /memory add <type>::<title>::<content>";
      input.put("type", parts[0]);
      input.put("title", parts[1]);
      input.put("content", parts[2]);
    } else if (rest.startsWith("delete ")) {
      toolName = "delete_memory";
      input.put("id", rest.substring("delete ".length()).trim());
    } else {
      return "Usage: /memory list [query] | /memory add <type>::<title>::<content> | /memory delete <id>";
    }
    var result = app.tools().execute(toolName, input, new ToolContext(app.cwd(), app.permissions()));
    return result.output();
  }

  // --- Helpers ---

  private static TranscriptEntry assistant(int id, String body) {
    return new TranscriptEntry.Assistant(id, body);
  }

  private static void runShortcutTool(String toolName, JsonNode input, TuiApp app) {
    app.addEntry(new TranscriptEntry.Tool(app.nextEntryId(), toolName, TranscriptEntry.ToolStatus.RUNNING, input.toString()));
    var result = app.tools().execute(toolName, input, new ToolContext(app.cwd(), app.permissions()));
    app.addRecentTool(toolName, !result.ok());
    app.addEntry(new TranscriptEntry.Tool(app.nextEntryId(), toolName,
        result.ok() ? TranscriptEntry.ToolStatus.SUCCESS : TranscriptEntry.ToolStatus.ERROR,
        result.output() == null ? "" : result.output()));
  }

  private static String[] splitShortcutPayload(String payload, int limit) {
    String[] parts = limit > 0 ? payload.split("::", limit) : payload.split("::", -1);
    for (int i = 0; i < parts.length; i++) {
      parts[i] = parts[i].trim();
    }
    return parts;
  }

  private static String parseCmdShortcut(String payload, Path cwd) {
    String[] parts = splitShortcutPayload(payload, 2);
    if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
      try {
        if (java.nio.file.Files.isDirectory(cwd.resolve(parts[0]).normalize())) {
          return parts[1].isBlank() ? parts[0] : parts[1];
        }
      } catch (Exception ignored) {
      }
    }
    return payload;
  }
}
