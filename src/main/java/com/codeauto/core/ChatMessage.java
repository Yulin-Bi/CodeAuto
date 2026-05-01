package com.codeauto.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "role")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ChatMessage.SystemMessage.class, name = "system"),
    @JsonSubTypes.Type(value = ChatMessage.UserMessage.class, name = "user"),
    @JsonSubTypes.Type(value = ChatMessage.AssistantMessage.class, name = "assistant"),
    @JsonSubTypes.Type(value = ChatMessage.AssistantRawMessage.class, name = "assistant_raw"),
    @JsonSubTypes.Type(value = ChatMessage.AssistantProgressMessage.class, name = "assistant_progress"),
    @JsonSubTypes.Type(value = ChatMessage.AssistantToolCallMessage.class, name = "assistant_tool_call"),
    @JsonSubTypes.Type(value = ChatMessage.ToolResultMessage.class, name = "tool_result"),
    @JsonSubTypes.Type(value = ChatMessage.ContextSummaryMessage.class, name = "context_summary")
})
public sealed interface ChatMessage permits ChatMessage.SystemMessage, ChatMessage.UserMessage,
    ChatMessage.AssistantMessage, ChatMessage.AssistantRawMessage, ChatMessage.AssistantProgressMessage,
    ChatMessage.AssistantToolCallMessage, ChatMessage.ToolResultMessage, ChatMessage.ContextSummaryMessage {

  String role();

  record SystemMessage(String content) implements ChatMessage {
    @Override public String role() { return "system"; }
  }

  record UserMessage(String content) implements ChatMessage {
    @Override public String role() { return "user"; }
  }

  record AssistantMessage(String content, ProviderUsage providerUsage, boolean usageStale) implements ChatMessage {
    public AssistantMessage(String content) { this(content, null, false); }
    @Override public String role() { return "assistant"; }
  }

  record AssistantRawMessage(JsonNode content, ProviderUsage providerUsage) implements ChatMessage {
    @Override public String role() { return "assistant_raw"; }
  }

  record AssistantProgressMessage(String content, ProviderUsage providerUsage) implements ChatMessage {
    public AssistantProgressMessage(String content) { this(content, null); }
    @Override public String role() { return "assistant_progress"; }
  }

  record AssistantToolCallMessage(String toolUseId, String toolName, JsonNode input, ProviderUsage providerUsage)
      implements ChatMessage {
    public AssistantToolCallMessage(String toolUseId, String toolName, JsonNode input) {
      this(toolUseId, toolName, input, null);
    }
    @Override public String role() { return "assistant_tool_call"; }
  }

  record ToolResultMessage(String toolUseId, String toolName, String content, boolean isError) implements ChatMessage {
    @Override public String role() { return "tool_result"; }
  }

  record ContextSummaryMessage(String content, int compressedCount, long timestamp) implements ChatMessage {
    @Override public String role() { return "context_summary"; }
  }
}
