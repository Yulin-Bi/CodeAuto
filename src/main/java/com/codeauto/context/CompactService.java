package com.codeauto.context;

import com.codeauto.core.ChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class CompactService {
  private CompactService() {
  }

  public static List<ChatMessage> compact(List<ChatMessage> messages, int keepTailMessages) {
    return compactWithStats(messages, keepTailMessages).messages();
  }

  public static CompactResult compactWithStats(List<ChatMessage> messages, int keepTailMessages) {
    return compactWithStats(messages, keepTailMessages, 200_000);
  }

  public static CompactResult compactWithStats(List<ChatMessage> messages, int keepTailMessages, int contextWindow) {
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
