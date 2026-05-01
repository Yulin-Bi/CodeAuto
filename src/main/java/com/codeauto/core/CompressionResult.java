package com.codeauto.core;

import java.util.List;

public record CompressionResult(
    List<ChatMessage> messages,
    ChatMessage.ContextSummaryMessage summary,
    int removedCount,
    int tokensBefore,
    int tokensAfter
) {
}
