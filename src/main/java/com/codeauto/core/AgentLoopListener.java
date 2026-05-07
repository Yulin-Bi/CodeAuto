package com.codeauto.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.context.CompactService;
import com.codeauto.context.ContextStats;
import java.util.List;

public interface AgentLoopListener {
  AgentLoopListener NOOP = new AgentLoopListener() {
  };

  default void onContextStats(ContextStats stats) {
  }

  default void onAutoCompact(CompactService.CompactResult result) {
  }

  default void onProgressMessage(String content) {
  }

  default void onAssistantDelta(String delta) {
  }

  default void onThinkingDelta(String delta) {
  }

  default void onAssistantMessage(String content) {
  }

  default void onToolStart(String toolName, JsonNode input) {
  }

  default void onToolResult(String toolName, String output, boolean isError) {
  }

  default void onTurnComplete(List<ChatMessage> messages) {
  }

  /** @param turnStartIndex index of the first message added in the current turn */
  default void onTurnComplete(List<ChatMessage> allMessages, int turnStartIndex) {
    onTurnComplete(allMessages);
  }
}
