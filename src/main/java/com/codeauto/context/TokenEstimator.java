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
    if (text == null || text.isEmpty()) return 1;
    // Count CJK characters separately: each can be 1–3 tokens, est. ~1.5 tokens each.
    // Non-CJK text averages ~4 chars/token.
    int cjk = 0;
    int other = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (Character.isHighSurrogate(ch) && i + 1 < text.length()) {
        int cp = Character.toCodePoint(ch, text.charAt(i + 1));
        i++;
        if (isCJK(cp)) { cjk++; } else { other++; }
      } else if (isCJK(ch)) {
        cjk++;
      } else {
        other++;
      }
    }
    // Rough: CJK ~1.5 tokens/char, ASCII ~0.25 tokens/char
    return Math.max(1, (int) (cjk * 1.5 + other * 0.25) + 4);
  }

  private static boolean isCJK(int codePoint) {
    return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)   // CJK Unified Ideographs
        || (codePoint >= 0x3400 && codePoint <= 0x4DBF)    // CJK Extension A
        || (codePoint >= 0xF900 && codePoint <= 0xFAFF)    // CJK Compatibility Ideographs
        || (codePoint >= 0x3040 && codePoint <= 0x309F)    // Hiragana
        || (codePoint >= 0x30A0 && codePoint <= 0x30FF)    // Katakana
        || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)    // Hangul Syllables
        || (codePoint >= 0x3000 && codePoint <= 0x303F)    // CJK Symbols/Punctuation
        || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);   // Fullwidth Forms
  }
}
