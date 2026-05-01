package com.codeauto.core;

import java.util.List;

public sealed interface AgentStep permits AgentStep.AssistantStep, AgentStep.ToolCallsStep {
  record AssistantStep(String content, Kind kind, ProviderUsage usage) implements AgentStep {
  }

  record ToolCallsStep(
      List<ToolCall> calls,
      String content,
      ContentKind contentKind,
      ProviderUsage usage,
      com.fasterxml.jackson.databind.JsonNode rawContent
  )
      implements AgentStep {
    public ToolCallsStep(List<ToolCall> calls, String content, ContentKind contentKind, ProviderUsage usage) {
      this(calls, content, contentKind, usage, null);
    }
  }

  enum Kind { FINAL, PROGRESS, UNSPECIFIED }

  enum ContentKind { PROGRESS, ASSISTANT }
}
