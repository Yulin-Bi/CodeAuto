package com.codeauto.context;

import com.codeauto.config.RuntimeConfig;
import com.codeauto.core.ChatMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public class ToolResultStorage {
  public static final int DEFAULT_MAX_RESULT_SIZE_CHARS = 50_000;
  public static final int MAX_TOOL_RESULTS_PER_BATCH_CHARS = 200_000;
  public static final int PREVIEW_SIZE_CHARS = 2_000;

  private final int thresholdChars;
  private final int previewChars;
  private final int batchBudgetChars;
  private final Map<String, String> replacements = new HashMap<>();

  public ToolResultStorage(int thresholdChars, int previewChars) {
    this(thresholdChars, previewChars, MAX_TOOL_RESULTS_PER_BATCH_CHARS);
  }

  public ToolResultStorage(int thresholdChars, int previewChars, int batchBudgetChars) {
    this.thresholdChars = thresholdChars;
    this.previewChars = previewChars;
    this.batchBudgetChars = batchBudgetChars;
  }

  public ToolResultStorage() {
    this(DEFAULT_MAX_RESULT_SIZE_CHARS, PREVIEW_SIZE_CHARS, MAX_TOOL_RESULTS_PER_BATCH_CHARS);
  }

  public ChatMessage.ToolResultMessage replaceIfLarge(ChatMessage.ToolResultMessage result) throws Exception {
    String previous = replacements.get(result.toolUseId());
    if (previous != null) {
      return new ChatMessage.ToolResultMessage(result.toolUseId(), result.toolName(), previous, result.isError());
    }
    String content = normalize(result.content());
    if (content.isBlank()) {
      return new ChatMessage.ToolResultMessage(
          result.toolUseId(), result.toolName(), "(" + result.toolName() + " completed with no output)", result.isError());
    }
    if (content.length() <= thresholdChars) {
      return result;
    }
    String replacement = persistReplacement(result.toolUseId(), result.toolName(), content);
    replacements.put(result.toolUseId(), replacement);
    return new ChatMessage.ToolResultMessage(result.toolUseId(), result.toolName(), replacement, result.isError());
  }

  public List<ChatMessage.ToolResultMessage> applyBatchBudget(List<ChatMessage.ToolResultMessage> results) throws Exception {
    if (results.isEmpty()) return results;

    List<ChatMessage.ToolResultMessage> normalized = new ArrayList<>();
    List<Candidate> candidates = new ArrayList<>();
    int visibleChars = 0;

    for (int i = 0; i < results.size(); i++) {
      ChatMessage.ToolResultMessage result = replaceIfLarge(results.get(i));
      normalized.add(result);
      String content = normalize(result.content());
      visibleChars += content.length();
      if (!replacements.containsKey(result.toolUseId()) && !content.isBlank()) {
        candidates.add(new Candidate(i, result.toolUseId(), result.toolName(), content, content.length()));
      }
    }

    if (visibleChars <= batchBudgetChars) {
      return normalized;
    }

    candidates.sort(Comparator
        .comparingInt(Candidate::size)
        .reversed()
        .thenComparing(Candidate::toolUseId));

    for (Candidate candidate : candidates) {
      if (visibleChars <= batchBudgetChars) break;
      String replacement = persistReplacement(candidate.toolUseId(), candidate.toolName(), candidate.content());
      replacements.put(candidate.toolUseId(), replacement);
      ChatMessage.ToolResultMessage original = normalized.get(candidate.index());
      normalized.set(candidate.index(),
          new ChatMessage.ToolResultMessage(original.toolUseId(), original.toolName(), replacement, original.isError()));
      visibleChars = visibleChars - candidate.size() + replacement.length();
    }

    return normalized;
  }

  private String persistReplacement(String toolUseId, String toolName, String content) throws Exception {
    Path file = persist(toolUseId, toolName, content);
    String preview = content.substring(0, Math.min(previewChars, content.length()));
    String replacement = """
        Large tool result stored outside the prompt context.
        tool: %s
        path: %s
        original_chars: %d

        preview:
        %s
        """.formatted(toolName, file, content.length(), preview);
    return replacement;
  }

  private static Path persist(String toolUseId, String toolName, String content) throws Exception {
    Path dir = RuntimeConfig.homeDir().resolve("tool-results");
    Files.createDirectories(dir);
    String hash = sha256(toolUseId + "\n" + content).substring(0, 16);
    Path file = dir.resolve(Instant.now().toEpochMilli() + "-" + sanitize(toolName) + "-" + hash + ".txt");
    Files.writeString(file, content);
    return file;
  }

  private static String normalize(String content) {
    return content == null ? "" : content;
  }

  private static String sanitize(String value) {
    String sanitized = value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    return sanitized.isBlank() ? "tool" : sanitized;
  }

  private static String sha256(String value) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private record Candidate(int index, String toolUseId, String toolName, String content, int size) {
  }
}
