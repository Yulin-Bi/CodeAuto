package com.codeauto.context;

import com.codeauto.core.ChatMessage;
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

  private CompactService() {
  }

  public static List<ChatMessage> compact(List<ChatMessage> messages, int keepTailMessages) {
    return compactWithStats(messages, keepTailMessages, 200_000, null).messages();
  }

  public static CompactResult compactWithStats(List<ChatMessage> messages, int keepTailMessages) {
    return compactWithStats(messages, keepTailMessages, 200_000, null);
  }

  public static CompactResult compactWithStats(List<ChatMessage> messages, int keepTailMessages, int contextWindow) {
    return compactWithStats(messages, keepTailMessages, contextWindow, null);
  }

  public static CompactResult compactWithStats(
      List<ChatMessage> messages, int keepTailMessages, int contextWindow, Path cwd) {
    ContextStats before = TokenEstimator.compute(messages, contextWindow);
    if (messages.size() <= keepTailMessages + 1) {
      return new CompactResult(messages, null, 0, before.estimatedTokens(), before.estimatedTokens());
    }
    ChatMessage system = messages.getFirst();
    int tailStart = Math.max(1, messages.size() - keepTailMessages);
    List<ChatMessage> compressed = messages.subList(1, tailStart);
    StringBuilder summary = new StringBuilder("Conversation compacted. Earlier messages summary:\n");
    for (ChatMessage message : compressed) {
      summary.append("- ").append(message.role()).append(": ").append(excerpt(message)).append("\n");
    }
    String artifactPath = null;
    if (cwd != null) {
      artifactPath = saveArtifact(cwd, summary.toString(), compressed);
      if (artifactPath != null) {
        summary.append("\n\nCompacted messages saved to ").append(artifactPath)
            .append(". If the summary above lacks details you need, read that file.");
      }
    }
    List<ChatMessage> next = new ArrayList<>();
    next.add(system);
    ChatMessage.ContextSummaryMessage summaryMessage =
        new ChatMessage.ContextSummaryMessage(summary.toString().trim(), compressed.size(), Instant.now().toEpochMilli());
    next.add(summaryMessage);
    for (ChatMessage message : messages.subList(tailStart, messages.size())) {
      next.add(markUsageStale(message));
    }
    ContextStats after = TokenEstimator.compute(next, contextWindow);
    return new CompactResult(next, summaryMessage, compressed.size(), before.estimatedTokens(), after.estimatedTokens());
  }

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
    String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    return normalized.length() > 160 ? normalized.substring(0, 160) + "..." : normalized;
  }

  public record CompactResult(
      List<ChatMessage> messages,
      ChatMessage.ContextSummaryMessage summary,
      int removedCount,
      int tokensBefore,
      int tokensAfter
  ) {
  }
}
