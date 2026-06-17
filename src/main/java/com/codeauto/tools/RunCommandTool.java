package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.background.BackgroundTaskRegistry;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RunCommandTool implements ToolDefinition {
  private static final int DEFAULT_TIMEOUT_SECONDS = 20;
  private static final int MAX_FOREGROUND_OUTPUT_BYTES = 512 * 1024;

  @Override public String name() { return "run_command"; }
  @Override public String description() { return "Run a local command in the workspace."; }
  @Override public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    props.set("command", JsonSchemas.stringProp("Shell command to run"));
    props.set("background", JsonSchemas.booleanProp("Run in background"));
    props.set("app_id", JsonSchemas.stringProp("Stable managed app identifier for background tasks"));
    props.set("health_url", JsonSchemas.stringProp("Optional readiness URL for managed background apps"));
    props.set("health_port", JsonSchemas.integerProp("Optional readiness TCP port for managed background apps"));
    props.set("startup_timeout", JsonSchemas.integerProp("Seconds to wait for readiness before failing (default: 20)"));
    props.set("timeout", JsonSchemas.integerProp("Timeout in seconds (default: 20)"));
    props.set("args", JsonSchemas.arrayProp("string", "Command arguments"));
    return JsonSchemas.required(schema, "command");
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String command = JsonSchemas.text(input, "command", "");
    if (command.isBlank()) return ToolResult.error("command is required");
    boolean background = input != null && input.path("background").asBoolean(false);
    String appId = JsonSchemas.text(input, "app_id", "").trim();
    String healthUrl = JsonSchemas.text(input, "health_url", "").trim();
    int healthPort = input != null && input.has("health_port") ? Math.max(0, input.path("health_port").asInt(0)) : 0;
    int startupTimeoutSec = input != null && input.has("startup_timeout")
        ? Math.clamp(input.path("startup_timeout").asInt(20), 1, 300)
        : 20;
    int timeoutSec = input != null && input.has("timeout")
        ? Math.clamp(input.path("timeout").asInt(DEFAULT_TIMEOUT_SECONDS), 1, 300)
        : DEFAULT_TIMEOUT_SECONDS;
    if (!background && (!healthUrl.isBlank() || healthPort > 0)) {
      return ToolResult.error("health_url/health_port are only supported for background tasks");
    }
    if (( !healthUrl.isBlank() || healthPort > 0) && appId.isBlank()) {
      return ToolResult.error("app_id is required when using health_url or health_port");
    }
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
    // Cross-platform check: detect Linux-only commands on Windows
    String compatError = checkCommandAvailability(executable);
    if (compatError != null) {
      return ToolResult.error(compatError);
    }
    if (background && !appId.isBlank() && BackgroundTaskRegistry.get().hasRunningAppId(appId)) {
      return ToolResult.error("Managed app already running: " + appId
          + ". Use background_tasks restart or cancel before starting another instance.");
    }
    Process process = new ProcessBuilder(parts)
        .directory(context.cwd().toFile())
        .redirectErrorStream(true)
        .start();
    if (background) {
      var task = BackgroundTaskRegistry.get().start(appId, command, parts, context.cwd(), process,
          healthUrl, healthPort, startupTimeoutSec);
      if (!healthUrl.isBlank() || healthPort > 0) {
        try {
          task = BackgroundTaskRegistry.get().awaitReady(task.id());
        } catch (IllegalStateException error) {
          return ToolResult.error("Managed app failed readiness check: " + error.getMessage());
        }
      }
      StringBuilder out = new StringBuilder("Started background task " + task.id());
      if (task.appId() != null && !task.appId().isBlank()) {
        out.append(" app=").append(task.appId());
      }
      out.append(" pid=").append(task.pid());
      if (task.healthStatus() != null && !task.healthStatus().isBlank()) {
        out.append(" health=").append(task.healthStatus());
      }
      return ToolResult.ok(out.toString());
    }
    OutputCollector collector = new OutputCollector(MAX_FOREGROUND_OUTPUT_BYTES);
    CompletableFuture<Void> outputFuture = CompletableFuture.runAsync(() -> {
      try {
        collector.readFrom(process.getInputStream());
      } catch (IOException error) {
        collector.recordError(error);
      }
    });
    boolean done = process.waitFor(Duration.ofSeconds(timeoutSec).toMillis(), TimeUnit.MILLISECONDS);
    if (!done) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      closeQuietly(process.getInputStream());
      return ToolResult.error("Command timed out after " + timeoutSec + "s: " + command);
    }
    String output;
    try {
      outputFuture.get(2, TimeUnit.SECONDS);
      output = collector.text();
    } catch (java.util.concurrent.TimeoutException timeout) {
      closeQuietly(process.getInputStream());
      output = collector.textWithSuffix(
          "[command output stream remained open after process exit; returning partial output]");
    }
    return new ToolResult(process.exitValue() == 0, output, false);
  }

  public static List<String> splitCommand(String command) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    Character quote = null;
    for (int i = 0; i < command.length(); i++) {
      char ch = command.charAt(i);
      if (quote != null) {
        if (ch == quote) {
          quote = null;
        } else if (quote == '"' && ch == '\\') {
          if (i + 1 < command.length()) {
            char next = command.charAt(i + 1);
            if (next == '"' || next == '\\') {
              current.append(next);
              i++;
            } else {
              current.append(ch);
            }
          } else {
            current.append(ch);
          }
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
      return List.of("cmd", "/d", "/c", stripTrailingBackgroundOperator(command));
    }
    return List.of("sh", "-c", stripTrailingBackgroundOperator(command));
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

  /**
   * Check if the executable is available on this platform. Returns an error message with
   * suggestions if the command is likely unavailable, or null if it should proceed.
   */
  static String checkCommandAvailability(String executable) {
    if (!isWindows()) return null;

    // Common Linux commands not available on Windows cmd
    var linuxCommands = Map.of(
        "head", "Use cmd /c \"more +<N> <file>\" or PowerShell Get-Content -Head",
        "tail", "Use PowerShell Get-Content -Tail or Get-Content -Wait",
        "wc", "Use PowerShell Measure-Object -Line -Word -Character",
        "grep", "Use findstr or PowerShell Select-String",
        "awk", "Use PowerShell ForEach-Object with -split",
        "sed", "Use PowerShell -replace operator",
        "diff", "Use fc (Windows) or Compare-Object (PowerShell)",
        "cat", "Use type (Windows) or Get-Content (PowerShell)",
        "less", "Use more (Windows) or type <file> | more",
        "touch", "Use copy /b nul <file> or PowerShell New-Item");

    String suggestion = linuxCommands.get(commandName(executable));
    if (suggestion != null) {
      return "'" + executable + "' is a Linux command not available on Windows cmd. "
          + suggestion + ".";
    }
    return null;
  }

  private static String commandName(String executable) {
    String normalized = executable == null ? "" : executable.trim().replace('\\', '/');
    int slash = normalized.lastIndexOf('/');
    String base = slash >= 0 ? normalized.substring(slash + 1) : normalized;
    return base.toLowerCase();
  }

  private static void closeQuietly(InputStream input) {
    try {
      input.close();
    } catch (IOException ignored) {
      // Best-effort cleanup only.
    }
  }

  private static final class OutputCollector {
    private final ByteArrayOutputStream output;
    private final int maxBytes;
    private int totalBytes;
    private boolean truncated;
    private String readError;

    private OutputCollector(int maxBytes) {
      this.output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
      this.maxBytes = maxBytes;
    }

    private synchronized void readFrom(InputStream input) throws IOException {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (read == 0) {
          continue;
        }
        if (totalBytes < maxBytes) {
          int keep = Math.min(read, maxBytes - totalBytes);
          output.write(buffer, 0, keep);
          if (keep < read) {
            truncated = true;
          }
        } else {
          truncated = true;
        }
        totalBytes += read;
      }
    }

    private synchronized void recordError(IOException error) {
      readError = error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private synchronized String text() {
      String text = output.toString(StandardCharsets.UTF_8);
      if (truncated) {
        text = appendLine(text, "[truncated command output after " + maxBytes + " bytes]");
      }
      if (readError != null && !readError.isBlank()) {
        text = appendLine(text, "[output reader error: " + readError + "]");
      }
      return text;
    }

    private synchronized String textWithSuffix(String suffix) {
      return appendLine(text(), suffix);
    }

    private static String appendLine(String base, String suffix) {
      if (base == null || base.isEmpty()) {
        return suffix;
      }
      return base.endsWith("\n") ? base + suffix : base + "\n" + suffix;
    }
  }
}
