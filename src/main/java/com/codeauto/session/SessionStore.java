package com.codeauto.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.config.RuntimeConfig;
import com.codeauto.core.ChatMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class SessionStore {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Path cwd;

  public SessionStore(Path cwd) {
    this.cwd = cwd.toAbsolutePath().normalize();
  }

  public void save(String sessionId, List<ChatMessage> messages, int alreadySavedCount) throws Exception {
    Path file = sessionFile(sessionId);
    Files.createDirectories(file.getParent());
    List<String> lines = new ArrayList<>();
    for (ChatMessage message : messages.subList(Math.min(alreadySavedCount, messages.size()), messages.size())) {
      lines.add(MAPPER.writeValueAsString(new SessionEvent(typeFor(message), message, UUID.randomUUID().toString(),
          Instant.now().toString(), sessionId, cwd.toString(), null, null, null, null)));
    }
    if (!lines.isEmpty()) {
      Files.writeString(file, String.join("\n", lines) + "\n",
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
  }

  public void rename(String sessionId, String title) throws Exception {
    Path file = sessionFile(sessionId);
    Files.createDirectories(file.getParent());
    SessionEvent event = new SessionEvent("rename", null, UUID.randomUUID().toString(), Instant.now().toString(),
        sessionId, cwd.toString(), title, null, null, null);
    Files.writeString(file, MAPPER.writeValueAsString(event) + "\n",
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
  }

  public void appendCompactBoundary(
      String sessionId,
      ChatMessage.ContextSummaryMessage summary,
      String trigger,
      int preTokens,
      int postTokens
  ) throws Exception {
    Path file = sessionFile(sessionId);
    Files.createDirectories(file.getParent());
    String now = Instant.now().toString();
    List<String> lines = List.of(
        MAPPER.writeValueAsString(new SessionEvent("compact_boundary", null, UUID.randomUUID().toString(), now,
            sessionId, cwd.toString(), null, trigger, preTokens, postTokens)),
        MAPPER.writeValueAsString(new SessionEvent("summary", summary, UUID.randomUUID().toString(), now,
            sessionId, cwd.toString(), null, null, null, null))
    );
    Files.writeString(file, String.join("\n", lines) + "\n",
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
  }

  public List<ChatMessage> load(String sessionId) throws Exception {
    Path file = sessionFile(sessionId);
    if (!Files.exists(file)) return List.of();
    List<String> lines = Files.readAllLines(file);
    int start = 0;
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (lines.get(i).isBlank()) continue;
      SessionEvent event = MAPPER.readValue(lines.get(i), SessionEvent.class);
      if ("compact_boundary".equals(event.type())) {
        start = i + 1;
        break;
      }
    }
    List<ChatMessage> messages = new ArrayList<>();
    for (int i = start; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank()) continue;
      SessionEvent event = MAPPER.readValue(line, SessionEvent.class);
      if (event.message != null) messages.add(event.message);
    }
    return messages;
  }

  public List<SessionSummary> list() throws Exception {
    Path dir = projectDir();
    if (!Files.isDirectory(dir)) return List.of();
    List<SessionSummary> summaries = new ArrayList<>();
    try (var paths = Files.list(dir)) {
      for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
        String id = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
        summaries.add(readSummary(id, file));
      }
    }
    summaries.sort(Comparator.comparing(SessionSummary::updatedAt).reversed());
    return summaries;
  }

  public int cleanupExpiredSessions(Duration maxAge) throws Exception {
    Path dir = projectDir();
    if (!Files.isDirectory(dir)) return 0;
    Instant cutoff = Instant.now().minus(maxAge);
    int removed = 0;
    try (var paths = Files.list(dir)) {
      for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
        try {
          if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
            Files.deleteIfExists(file);
            removed++;
          }
        } catch (Exception ignored) {
          // A locked or malformed session file should not block cleanup.
        }
      }
    }
    try (var remaining = Files.list(dir)) {
      if (remaining.findAny().isEmpty()) {
        Files.deleteIfExists(dir);
      }
    } catch (Exception ignored) {
      // Directory cleanup is best-effort.
    }
    return removed;
  }

  public List<TranscriptEntry> loadTranscript(String sessionId) throws Exception {
    Path file = sessionFile(sessionId);
    if (!Files.exists(file)) return List.of();
    List<TranscriptEntry> entries = new ArrayList<>();
    for (String line : Files.readAllLines(file)) {
      if (line.isBlank()) continue;
      SessionEvent event = MAPPER.readValue(line, SessionEvent.class);
      switch (event.type()) {
        case "user" -> entries.add(new TranscriptEntry("user", null, textContent(event.message()), null));
      case "assistant" -> entries.add(new TranscriptEntry("assistant", null, textContent(event.message()), null));
      case "assistant_raw" -> entries.add(new TranscriptEntry("assistant", null, textContent(event.message()), null));
        case "progress" -> entries.add(new TranscriptEntry("progress", null, textContent(event.message()), null));
        case "tool_call" -> {
          String toolName = event.message() instanceof ChatMessage.AssistantToolCallMessage call
              ? call.toolName()
              : "unknown";
          entries.add(new TranscriptEntry("tool", toolName, toolInput(event.message()), "success"));
        }
        case "tool_result" -> {
          String toolName = event.message() instanceof ChatMessage.ToolResultMessage result
              ? result.toolName()
              : "unknown";
          String status = event.message() instanceof ChatMessage.ToolResultMessage result && result.isError()
              ? "error"
              : "success";
          entries.add(new TranscriptEntry("tool_result", toolName, textContent(event.message()), status));
        }
        case "summary" -> {
          int count = event.message() instanceof ChatMessage.ContextSummaryMessage summary
              ? summary.compressedCount()
              : 0;
          entries.add(new TranscriptEntry(
              "assistant", null, "[Context summary: " + count + " messages compressed]", null));
        }
        case "compact_boundary" -> entries.add(new TranscriptEntry(
            "assistant",
            null,
            "[Context compacted: " + valueOrUnknown(event.preTokens()) + " -> "
                + valueOrUnknown(event.postTokens()) + " tokens]",
            null));
        default -> {
          // Ignore metadata events such as rename.
        }
      }
    }
    return entries;
  }

  public static List<ProjectMeta> listAllProjects() throws Exception {
    Path projectsDir = RuntimeConfig.homeDir().resolve("projects");
    if (!Files.isDirectory(projectsDir)) return List.of();
    List<ProjectMeta> projects = new ArrayList<>();
    try (var paths = Files.list(projectsDir)) {
      for (Path dir : paths.filter(Files::isDirectory).toList()) {
        ProjectMeta meta = readProjectMeta(dir);
        if (meta.sessionCount() > 0) {
          projects.add(meta);
        }
      }
    }
    projects.sort(Comparator.comparingLong(ProjectMeta::latestUpdatedAt).reversed());
    return projects;
  }

  private Path sessionFile(String sessionId) {
    return projectDir().resolve(sessionId + ".jsonl");
  }

  private Path projectDir() {
    String project = cwd.toString().replaceAll("[/\\\\:]+", "-").replaceAll("^-+", "");
    return RuntimeConfig.homeDir().resolve("projects").resolve(project);
  }

  private static SessionSummary readSummary(String id, Path file) throws Exception {
    String title = null;
    String firstUser = null;
    String updatedAt = "";
    for (String line : Files.readAllLines(file)) {
      if (line.isBlank()) continue;
      SessionEvent event = MAPPER.readValue(line, SessionEvent.class);
      updatedAt = event.timestamp() == null ? updatedAt : event.timestamp();
      if ("rename".equals(event.type()) && event.title() != null && !event.title().isBlank()) {
        title = event.title();
      }
      if (firstUser == null && event.message() instanceof ChatMessage.UserMessage user) {
        firstUser = user.content();
      }
    }
    if (title == null || title.isBlank()) title = firstUser == null ? "(untitled)" : excerpt(firstUser);
    return new SessionSummary(id, title, updatedAt);
  }

  public static List<SessionSummary> listSessions(String storageName) throws Exception {
    Path dir = RuntimeConfig.homeDir().resolve("projects").resolve(storageName);
    if (!Files.isDirectory(dir)) return List.of();
    List<SessionSummary> summaries = new ArrayList<>();
    try (var paths = Files.list(dir)) {
      for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
        String id = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
        summaries.add(readSummary(id, file));
      }
    }
    summaries.sort(Comparator.comparing(SessionSummary::updatedAt).reversed());
    return summaries;
  }

  public static List<ChatMessage> loadSession(String storageName, String sessionId) throws Exception {
    Path dir = RuntimeConfig.homeDir().resolve("projects").resolve(storageName);
    Path file = dir.resolve(sessionId + ".jsonl");
    if (!Files.exists(file)) return List.of();
    List<String> lines = Files.readAllLines(file);
    int start = 0;
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (lines.get(i).isBlank()) continue;
      SessionEvent event = MAPPER.readValue(lines.get(i), SessionEvent.class);
      if ("compact_boundary".equals(event.type())) {
        start = i + 1;
        break;
      }
    }
    List<ChatMessage> messages = new ArrayList<>();
    for (int i = start; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank()) continue;
      SessionEvent event = MAPPER.readValue(line, SessionEvent.class);
      if (event.message != null) messages.add(event.message);
    }
    return messages;
  }

  private static ProjectMeta readProjectMeta(Path dir) throws Exception {
    int sessionCount = 0;
    long latestUpdatedAt = 0;
    String cwd = null;
    try (var paths = Files.list(dir)) {
      for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
        sessionCount++;
        long modified = Files.getLastModifiedTime(file).toMillis();
        latestUpdatedAt = Math.max(latestUpdatedAt, modified);
        if (cwd == null) {
          cwd = firstCwd(file);
        }
      }
    }
    String display = cwd == null || cwd.isBlank() ? dir.getFileName().toString() : cwd;
    return new ProjectMeta(display, dir.getFileName().toString(), sessionCount, latestUpdatedAt);
  }

  private static String firstCwd(Path file) {
    try {
      for (String line : Files.readAllLines(file)) {
        if (line.isBlank()) continue;
        SessionEvent event = MAPPER.readValue(line, SessionEvent.class);
        if (event.cwd() != null && !event.cwd().isBlank()) {
          return event.cwd();
        }
      }
    } catch (Exception ignored) {
      // Best-effort metadata only.
    }
    return null;
  }

  private static String excerpt(String text) {
    String normalized = text.replaceAll("\\s+", " ").trim();
    return normalized.length() > 60 ? normalized.substring(0, 60) + "..." : normalized;
  }

  private static String typeFor(ChatMessage message) {
    return switch (message.role()) {
      case "system" -> "system";
      case "user" -> "user";
      case "assistant" -> "assistant";
      case "assistant_raw" -> "assistant_raw";
      case "assistant_progress" -> "progress";
      case "assistant_tool_call" -> "tool_call";
      case "tool_result" -> "tool_result";
      default -> "summary";
    };
  }

  private static String textContent(ChatMessage message) {
    if (message == null) return "";
    return switch (message) {
      case ChatMessage.SystemMessage m -> m.content();
      case ChatMessage.UserMessage m -> m.content();
      case ChatMessage.AssistantMessage m -> m.content();
      case ChatMessage.AssistantRawMessage m -> rawAssistantText(m.content());
      case ChatMessage.AssistantProgressMessage m -> m.content();
      case ChatMessage.ToolResultMessage m -> m.content();
      case ChatMessage.ContextSummaryMessage m -> m.content();
      case ChatMessage.AssistantToolCallMessage m -> m.toolName() + " " + m.input();
    };
  }

  private static String toolInput(ChatMessage message) {
    if (message instanceof ChatMessage.AssistantToolCallMessage call) {
      return call.input() == null ? "" : call.input().toString();
    }
    return "";
  }

  private static String rawAssistantText(com.fasterxml.jackson.databind.JsonNode content) {
    if (content == null || !content.isArray()) return "";
    var text = new StringBuilder();
    int toolUses = 0;
    boolean hasThinking = false;
    for (com.fasterxml.jackson.databind.JsonNode block : content) {
      String type = block.path("type").asText();
      if ("text".equals(type) && !block.path("text").asText("").isBlank()) {
        if (!text.isEmpty()) text.append("\n");
        text.append(block.path("text").asText());
      } else if ("tool_use".equals(type)) {
        toolUses++;
      } else if ("thinking".equals(type)) {
        hasThinking = true;
      }
    }
    if (!text.isEmpty()) return text.toString();
    if (toolUses > 0) return "[assistant requested " + toolUses + " tool call" + (toolUses == 1 ? "" : "s") + "]";
    return hasThinking ? "[assistant thinking block]" : "";
  }

  private static String valueOrUnknown(Integer value) {
    return value == null ? "?" : value.toString();
  }

  public record SessionEvent(
      String type,
      ChatMessage message,
      String uuid,
      String timestamp,
      String sessionId,
      String cwd,
      String title,
      String compactTrigger,
      Integer preTokens,
      Integer postTokens
  ) {
  }

  public record SessionSummary(String id, String title, String updatedAt) {
  }

  public record TranscriptEntry(String kind, String toolName, String body, String status) {
  }

  public record ProjectMeta(String cwd, String storageName, int sessionCount, long latestUpdatedAt) {
  }
}
