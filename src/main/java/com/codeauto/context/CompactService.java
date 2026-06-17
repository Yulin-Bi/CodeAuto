package com.codeauto.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.core.AgentStep;
import com.codeauto.core.ChatMessage;
import com.codeauto.model.ModelAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class CompactService {
  private static final DateTimeFormatter TIMESTAMP_FMT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private static final String COMPACTION_SYSTEM_PROMPT = """
      You are a conversation summarizer for an AI coding agent. Summarize the conversation history below.

      Output in this EXACT Markdown structure (use ### headers):

      ### User Intent
      [What the user wanted to accomplish. 1-3 lines.]

      ### Key Decisions
      [Design choices, architectural decisions, technical approaches chosen. One per line with -. Omit if none.]

      ### File Changes
      [Files modified, created, or examined. Include paths. One per line with -. Omit if none.]

      ### Errors & Fixes
      [Errors encountered and how they were resolved. One per line with -. Omit if none.]

      ### TODOs
      [Pending work items. One per line with - [ ]. Omit if none.]

      ### Important Context
      [Other critical information not captured above. Keep concise.]

      Rules:
      - Be CONCISE. Each section max 5 lines.
      - Prioritize DECISIONS, ERRORS, and TODOs over routine operations.
      - Omit repetitive tool outputs (e.g., multiple similar file reads).
      - Write "None." for sections with no content.
      - Output TEXT ONLY. Do NOT use tool calls. Do NOT output XML tags.
      - Output language should match the conversation's primary language.
      """;

  private CompactService() {
  }

  // ---- public API ----

  public static List<ChatMessage> compact(List<ChatMessage> messages, int keepTailMessages) {
    return compactWithStats(messages, keepTailMessages, 200_000, null, null).messages();
  }

  public static CompactResult compactWithStats(List<ChatMessage> messages, int keepTailMessages) {
    return compactWithStats(messages, keepTailMessages, 200_000, null, null);
  }

  public static CompactResult compactWithStats(List<ChatMessage> messages, int keepTailMessages, int contextWindow) {
    return compactWithStats(messages, keepTailMessages, contextWindow, null, null);
  }

  public static CompactResult compactWithStats(
      List<ChatMessage> messages, int keepTailMessages, int contextWindow, Path cwd) {
    return compactWithStats(messages, keepTailMessages, contextWindow, cwd, null);
  }

  public static CompactResult compactWithStats(
      List<ChatMessage> messages, int keepTailMessages, int contextWindow, Path cwd, ModelAdapter model) {
    ContextStats before = TokenEstimator.compute(messages, contextWindow);
    if (messages.size() <= keepTailMessages + 1) {
      return new CompactResult(messages, null, 0, before.estimatedTokens(), before.estimatedTokens());
    }
    ChatMessage system = messages.getFirst();
    // Find last ContextSummaryMessage as compaction boundary — everything before it is locked.
    int compactFrom = findLastSummaryIndex(messages) + 1;
    if (compactFrom < 1) compactFrom = 1;
    // If everything after the boundary fits in the tail, nothing to compress.
    if (messages.size() - compactFrom <= keepTailMessages) {
      return new CompactResult(messages, null, 0, before.estimatedTokens(), before.estimatedTokens());
    }
    int tailStart = Math.max(compactFrom + 1, messages.size() - keepTailMessages);
    tailStart = snapToCleanBoundary(messages, tailStart);
    List<ChatMessage> compressed = messages.subList(compactFrom, tailStart);

    String summary = generateSummary(model, compressed);

    String artifactPath = null;
    if (cwd != null) {
      artifactPath = saveArtifact(cwd, summary, compressed);
      if (artifactPath != null) {
        summary += "\n\nCompacted messages saved to " + artifactPath
            + ". If the summary above lacks details you need, grep that file for relevant keywords "
            + "— do NOT read the entire file.";
      }
    }

    List<ChatMessage> next = new ArrayList<>();
    next.add(system);
    // Preserve locked messages (index 1 through compactFrom-1, includes prior summaries).
    for (int i = 1; i < compactFrom; i++) {
      next.add(messages.get(i));
    }
    ChatMessage.ContextSummaryMessage summaryMessage =
        new ChatMessage.ContextSummaryMessage(summary.trim(), compressed.size(), Instant.now().toEpochMilli());
    next.add(summaryMessage);
    for (int i = tailStart; i < messages.size(); i++) {
      next.add(markUsageStale(messages.get(i)));
    }
    ContextStats after = TokenEstimator.compute(next, contextWindow);
    return new CompactResult(next, summaryMessage, compressed.size(), before.estimatedTokens(), after.estimatedTokens());
  }

  // ---- summary generation ----

  private static String generateSummary(ModelAdapter model, List<ChatMessage> compressed) {
    if (model != null) {
      String modelSummary = summarizeWithModel(model, compressed);
      if (modelSummary != null && !modelSummary.isBlank()) {
        return modelSummary;
      }
    }
    return buildHeuristicSummary(compressed);
  }

  private static String summarizeWithModel(ModelAdapter model, List<ChatMessage> compressed) {
    List<ChatMessage> request = new ArrayList<>();
    request.add(new ChatMessage.SystemMessage(COMPACTION_SYSTEM_PROMPT));

    StringBuilder body = new StringBuilder("Summarize this conversation history:\n\n");
    for (int i = 0; i < compressed.size(); i++) {
      body.append("[").append(i + 1).append("] ");
      body.append(formatForModel(compressed.get(i)));
      body.append("\n\n");
    }
    request.add(new ChatMessage.UserMessage(body.toString()));

    try {
      AgentStep step = model.next(request);
      if (step instanceof AgentStep.AssistantStep assistant) {
        String content = assistant.content();
        if (content != null && !content.isBlank()) {
          return content.trim();
        }
      }
    } catch (Exception ignored) {
      // model call failed — fall back to heuristic
    }
    return null;
  }

  // ---- heuristic summary (improved fallback) ----

  private static String buildHeuristicSummary(List<ChatMessage> compressed) {
    List<String> userMsgs = new ArrayList<>();
    List<String> toolCalls = new ArrayList<>();
    List<String> toolResults = new ArrayList<>();
    List<String> assistantMsgs = new ArrayList<>();

    for (ChatMessage msg : compressed) {
      if (msg instanceof ChatMessage.UserMessage m) {
        userMsgs.add(truncate(m.content(), 300));
      } else if (msg instanceof ChatMessage.AssistantToolCallMessage m) {
        toolCalls.add(m.toolName() + ": " + truncate(String.valueOf(m.input()), 200));
      } else if (msg instanceof ChatMessage.ToolResultMessage m) {
        toolResults.add("[" + m.toolName() + "] " + extractKey(m.content()));
      } else if (msg instanceof ChatMessage.AssistantMessage m) {
        assistantMsgs.add(truncate(compressWS(m.content()), 300));
      } else if (msg instanceof ChatMessage.ContextSummaryMessage m) {
        assistantMsgs.add("(prior compaction) " + truncate(compressWS(m.content()), 200));
      }
      // skip progress and raw messages — low value
    }

    StringBuilder sb = new StringBuilder("Conversation compacted. Summary:\n\n");

    if (!userMsgs.isEmpty()) {
      sb.append("### User Requests\n");
      for (String s : userMsgs) {
        sb.append("- ").append(s).append("\n");
      }
      sb.append("\n");
    }
    if (!toolCalls.isEmpty()) {
      sb.append("### Tools Called\n");
      for (String s : toolCalls) {
        sb.append("- ").append(s).append("\n");
      }
      sb.append("\n");
    }
    if (!toolResults.isEmpty()) {
      sb.append("### Key Outputs\n");
      for (String s : toolResults) {
        sb.append("- ").append(s).append("\n");
      }
      sb.append("\n");
    }
    if (!assistantMsgs.isEmpty()) {
      sb.append("### Assistant Responses\n");
      for (String s : assistantMsgs) {
        sb.append("- ").append(s).append("\n");
      }
      sb.append("\n");
    }
    if (userMsgs.isEmpty() && toolCalls.isEmpty() && toolResults.isEmpty() && assistantMsgs.isEmpty()) {
      sb.append("(No compressible messages)\n");
    }

    return sb.toString().trim();
  }

  private static String extractKey(String content) {
    if (content == null || content.isBlank()) return "(empty)";
    String[] lines = content.split("\n");
    StringBuilder key = new StringBuilder();
    int count = 0;
    for (String line : lines) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) {
        if (count > 0) key.append(" | ");
        key.append(truncate(trimmed, 150));
        count++;
        if (count >= 3) break;
      }
    }
    return key.isEmpty() ? "(whitespace only)" : key.toString();
  }

  // ---- formatting helpers ----

  private static String formatForModel(ChatMessage message) {
    String role = message.role();
    return switch (message) {
      case ChatMessage.UserMessage m -> role + ":\n" + truncate(m.content(), 8_000);
      case ChatMessage.AssistantMessage m -> role + ":\n" + truncate(m.content(), 8_000);
      case ChatMessage.AssistantToolCallMessage m ->
          role + ": " + m.toolName() + "\nInput: " + truncate(String.valueOf(m.input()), 4_000);
      case ChatMessage.ToolResultMessage m ->
          role + " [" + m.toolName() + "]:\n" + truncate(m.content(), 8_000);
      case ChatMessage.AssistantProgressMessage m -> role + " (progress):\n" + truncate(m.content(), 1_000);
      case ChatMessage.ContextSummaryMessage m -> role + " (prior summary):\n" + truncate(m.content(), 1_000);
      default -> role + ":\n" + truncate(compressWS(excerpt(message)), 1_000);
    };
  }

  // ---- artifact persistence (unchanged) ----

  private static String saveArtifact(Path cwd, String summary, List<ChatMessage> compressed) {
    try {
      Path dir = cwd.resolve(".codeauto").resolve("compacted");
      Files.createDirectories(dir);
      String timestamp = TIMESTAMP_FMT.format(Instant.now());
      String filename = "compact-" + timestamp + ".md";
      Path file = dir.resolve(filename);
      StringBuilder md = new StringBuilder();
      md.append("# Compacted Context — ").append(Instant.now()).append("\n\n");
      md.append("## Summary\n\n");
      md.append(summary).append("\n\n");
      md.append("## Messages (").append(compressed.size()).append(" messages)\n\n");
      for (int i = 0; i < compressed.size(); i++) {
        ChatMessage message = compressed.get(i);
        md.append("### ").append(i + 1).append(". ").append(message.role()).append("\n\n");
        md.append("```\n");
        md.append(formatMessageBody(message));
        md.append("\n```\n\n");
      }
      Files.writeString(file, md.toString());
      return ".codeauto/compacted/" + filename;
    } catch (Exception e) {
      return null;
    }
  }

  private static String formatMessageBody(ChatMessage message) {
    return switch (message) {
      case ChatMessage.SystemMessage m -> m.content();
      case ChatMessage.UserMessage m -> m.content();
      case ChatMessage.AssistantMessage m -> m.content();
      case ChatMessage.AssistantRawMessage m -> m.content() == null ? "" : m.content().toString();
      case ChatMessage.AssistantProgressMessage m -> m.content();
      case ChatMessage.ToolResultMessage m -> "[" + m.toolName() + "] " + m.content();
      case ChatMessage.ContextSummaryMessage m -> m.content();
      case ChatMessage.AssistantToolCallMessage m -> "Tool: " + m.toolName() + "\nInput: " + m.input();
    };
  }

  // ---- small utilities ----

  /**
   * Find the index of the last {@link ChatMessage.ContextSummaryMessage} in the list.
   * This marks the compaction boundary — everything before it (inclusive) is locked
   * and won't be re-compressed. Returns -1 if no summary exists.
   */
  private static int findLastSummaryIndex(List<ChatMessage> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof ChatMessage.ContextSummaryMessage) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Adjust index so the compact boundary never splits tool_use / tool_result pairs.
   * If {@code messages[index]} is a {@link ToolResultMessage}, walk backwards past all
   * adjacent ToolResultMessages and the AssistantToolCallMessages that precede them,
   * so the tail includes the complete tool turn.
   */
  private static int snapToCleanBoundary(List<ChatMessage> messages, int index) {
    if (index <= 1 || index >= messages.size()) return index;
    if (!(messages.get(index) instanceof ChatMessage.ToolResultMessage)) return index;
    int i = index;
    while (i > 1 && messages.get(i - 1) instanceof ChatMessage.ToolResultMessage) {
      i--;
    }
    while (i > 1) {
      ChatMessage previous = messages.get(i - 1);
      if (previous instanceof ChatMessage.AssistantToolCallMessage) {
        i--;
        continue;
      }
      if (previous instanceof ChatMessage.AssistantRawMessage raw && containsToolUseBlock(raw.content())) {
        i--;
        continue;
      }
      break;
    }
    return i;
  }

  private static boolean containsToolUseBlock(JsonNode content) {
    if (content == null || !content.isArray()) {
      return false;
    }
    for (JsonNode block : content) {
      if ("tool_use".equals(block.path("type").asText())) {
        return true;
      }
    }
    return false;
  }

  private static ChatMessage markUsageStale(ChatMessage message) {
    if (message instanceof ChatMessage.AssistantMessage assistant && assistant.providerUsage() != null) {
      return new ChatMessage.AssistantMessage(assistant.content(), assistant.providerUsage(), true);
    }
    return message;
  }

  private static String excerpt(ChatMessage message) {
    String text = switch (message) {
      case ChatMessage.SystemMessage m -> m.content();
      case ChatMessage.UserMessage m -> m.content();
      case ChatMessage.AssistantMessage m -> m.content();
      case ChatMessage.AssistantRawMessage m -> m.content() == null ? "" : m.content().toString();
      case ChatMessage.AssistantProgressMessage m -> m.content();
      case ChatMessage.ToolResultMessage m -> m.content();
      case ChatMessage.ContextSummaryMessage m -> m.content();
      case ChatMessage.AssistantToolCallMessage m -> m.toolName() + " " + m.input();
    };
    return text == null ? "" : text;
  }

  private static String truncate(String text, int maxLen) {
    if (text == null) return "";
    String normalized = text.replaceAll("\\s+", " ").trim();
    return normalized.length() > maxLen ? normalized.substring(0, maxLen) + "..." : normalized;
  }

  private static String compressWS(String text) {
    return text == null ? "" : text.replaceAll("\\s+", " ").trim();
  }

  // ---- result type ----

  public record CompactResult(
      List<ChatMessage> messages,
      ChatMessage.ContextSummaryMessage summary,
      int removedCount,
      int tokensBefore,
      int tokensAfter
  ) {
  }
}
