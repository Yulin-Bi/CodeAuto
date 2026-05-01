package com.codeauto.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.context.CompactService;
import com.codeauto.context.ContextStats;

public interface AgentLoopListener {
  AgentLoopListener NOOP = new AgentLoopListener() {
  };

  default void onContextStats(ContextStats stats) {
  }

  default void onAutoCompact(CompactService.CompactResult result) {
  }

  default void onProgressMessage(String content) {
  }

  default void onAssistantMessage(String content) {
  }

  default void onToolStart(String toolName, JsonNode input) {
  }

  default void onToolResult(String toolName, String output, boolean isError) {
  }
}
