package com.codeauto.context;

import com.codeauto.core.ChatMessage;
import java.util.List;

public final class TokenEstimator {
  private TokenEstimator() {
  }

  public static ContextStats compute(List<ChatMessage> messages, int contextWindow) {
    int tokens = messages.stream().mapToInt(TokenEstimator::estimateMessage).sum();
    double ratio = contextWindow <= 0 ? 0 : (double) tokens / contextWindow;
    String level = ratio >= 0.95 ? "blocked" : ratio >= 0.8 ? "critical" : ratio >= 0.6 ? "warning" : "ok";
    return new ContextStats(tokens, messages.size(), level);
  }

  private static int estimateMessage(ChatMessage message) {
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
    return Math.max(1, (text == null ? 0 : text.length()) / 4 + 4);
  }
}
