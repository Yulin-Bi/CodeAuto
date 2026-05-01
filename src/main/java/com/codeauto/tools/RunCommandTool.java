package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.background.BackgroundTaskRegistry;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RunCommandTool implements ToolDefinition {
  private static final int DEFAULT_TIMEOUT_SECONDS = 20;

  @Override public String name() { return "run_command"; }
  @Override public String description() { return "Run a local command in the workspace."; }
  @Override public JsonNode inputSchema() { return JsonSchemas.objectSchema(); }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String command = JsonSchemas.text(input, "command", "");
    if (command.isBlank()) return ToolResult.error("command is required");
    boolean background = input != null && input.path("background").asBoolean(false);
    int timeoutSec = input != null && input.has("timeout")
        ? Math.max(1, input.path("timeout").asInt(DEFAULT_TIMEOUT_SECONDS))
        : DEFAULT_TIMEOUT_SECONDS;
    List<String> parts;
    try {
      parts = normalizeCommandInput(input, command);
    } catch (IllegalArgumentException error) {
      return ToolResult.error(error.getMessage());
    }
    String executable = parts.getFirst();
    List<String> args = parts.subList(1, parts.size());
    if (!context.permissions().canRun(executable, args)) {
      String reason = context.permissions().classifyDangerousCommand(executable, args);
      return ToolResult.error("Command requires approval: " + (reason == null ? command : reason)
          + context.permissions().formatLastDenialFeedback());
    }
    Process process = new ProcessBuilder(parts)
        .directory(context.cwd().toFile())
        .redirectErrorStream(true)
        .start();
    if (background) {
      var task = BackgroundTaskRegistry.get().start(command, process);
      return ToolResult.ok("Started background task " + task.id() + " pid=" + task.pid());
    }
    CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
      try {
        return new String(process.getInputStream().readAllBytes());
      } catch (IOException error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
      }
    });
    boolean done = process.waitFor(Duration.ofSeconds(timeoutSec).toMillis(), TimeUnit.MILLISECONDS);
    if (!done) {
      process.destroyForcibly();
      return ToolResult.error("Command timed out after " + timeoutSec + "s: " + command);
    }
    String output = outputFuture.get(2, TimeUnit.SECONDS);
    return new ToolResult(process.exitValue() == 0, output, false);
  }

  public static List<String> splitCommand(String command) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    Character quote = null;
    boolean escaping = false;
    for (int i = 0; i < command.length(); i++) {
      char ch = command.charAt(i);
      if (escaping) {
        current.append(ch);
        escaping = false;
        continue;
      }
      if (ch == '\\') {
        escaping = true;
        continue;
      }
      if (quote != null) {
        if (ch == quote) {
          quote = null;
        } else {
          current.append(ch);
        }
        continue;
      }
      if (ch == '"' || ch == '\'') {
        quote = ch;
        continue;
      }
      if (Character.isWhitespace(ch)) {
        if (!current.isEmpty()) {
          parts.add(current.toString());
          current.setLength(0);
        }
        continue;
      }
      current.append(ch);
    }
    if (escaping) {
      current.append('\\');
    }
    if (quote != null) {
      throw new IllegalArgumentException("Unclosed quote in command");
    }
    if (!current.isEmpty()) {
      parts.add(current.toString());
    }
    return parts;
  }

  private static List<String> normalizeCommandInput(JsonNode input, String command) {
    if (input != null && input.has("args") && input.path("args").isArray() && input.path("args").size() > 0) {
      List<String> parts = new ArrayList<>();
      parts.add(command.trim());
      for (JsonNode arg : input.path("args")) {
        parts.add(arg.asText());
      }
      return parts;
    }

    if (looksLikeShellSnippet(command)) {
      return shellCommand(command);
    }

    List<String> parts = splitCommand(command.trim());
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("command is required");
    }
    if (isWindows() && isWindowsShellBuiltin(parts.getFirst())) {
      return shellCommand(command);
    }
    return parts;
  }

  private static boolean looksLikeShellSnippet(String command) {
    return command.matches(".*[|&;<>()$`].*");
  }

  private static List<String> shellCommand(String command) {
    if (isWindows()) {
      return List.of("cmd", "/c", stripTrailingBackgroundOperator(command));
    }
    return List.of("sh", "-lc", stripTrailingBackgroundOperator(command));
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }

  private static boolean isWindowsShellBuiltin(String command) {
    String name = command.toLowerCase();
    return List.of(
        "assoc", "break", "call", "cd", "chdir", "cls", "color", "copy", "date",
        "del", "dir", "echo", "endlocal", "erase", "exit", "for", "ftype", "if",
        "md", "mkdir", "mklink", "move", "path", "pause", "popd", "prompt", "pushd",
        "rd", "ren", "rename", "rmdir", "set", "setlocal", "shift", "start", "time",
        "title", "type", "ver", "verify", "vol"
    ).contains(name);
  }

  private static String stripTrailingBackgroundOperator(String command) {
    String trimmed = command.trim();
    return trimmed.endsWith("&") && !trimmed.endsWith("&&")
        ? trimmed.substring(0, trimmed.length() - 1).trim()
        : trimmed;
  }
}
