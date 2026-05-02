package com.codeauto.tui;

import com.codeauto.core.ChatMessage;
import java.util.ArrayList;
import java.util.List;

public sealed interface TranscriptEntry {
  int id();

  record User(int id, String body) implements TranscriptEntry {}
  record Assistant(int id, String body) implements TranscriptEntry {}
  record Tool(int id, String toolName, ToolStatus status, String body) implements TranscriptEntry {}
  record Progress(int id, String body) implements TranscriptEntry {}
  /** Transient status line with spinner animation during AgentLoop execution. */
  record Status(int id, String body) implements TranscriptEntry {}

  enum ToolStatus { RUNNING, SUCCESS, ERROR }

  static List<TranscriptEntry> fromMessages(List<ChatMessage> messages) {
    List<TranscriptEntry> entries = new ArrayList<>();
    int id = 0;
    for (var msg : messages) {
      if (msg instanceof ChatMessage.SystemMessage) continue;
      if (msg instanceof ChatMessage.UserMessage um) {
        entries.add(new TranscriptEntry.User(id++, um.content()));
      } else if (msg instanceof ChatMessage.AssistantMessage am) {
        entries.add(new TranscriptEntry.Assistant(id++, formatAssistantBody(am.content())));
      } else if (msg instanceof ChatMessage.AssistantRawMessage raw) {
        entries.add(new TranscriptEntry.Assistant(id++, formatAssistantBody(rawAssistantText(raw.content()))));
      } else if (msg instanceof ChatMessage.AssistantToolCallMessage tc) {
        entries.add(new TranscriptEntry.Tool(id++, tc.toolName(),
            TranscriptEntry.ToolStatus.SUCCESS, summarizeToolInput(tc.toolName(), tc.input())));
      }
    }
    return entries;
  }

  private static String formatAssistantBody(String content) {
    if (content == null) return "";
    if (content.length() > 5000) return content.substring(0, 5000) + "...\n[truncated]";
    return content;
  }

  private static String summarizeToolInput(String toolName, Object input) {
    if (input == null) return "";
    String json = input.toString();
    if (json.length() > 200) json = json.substring(0, 200) + "...";
    return toolName + " " + json;
  }

  private static String rawAssistantText(com.fasterxml.jackson.databind.JsonNode content) {
    if (content == null || !content.isArray()) return "";
    var text = new StringBuilder();
    int toolUses = 0;
    boolean hasThinking = false;
    for (com.fasterxml.jackson.databind.JsonNode block : content) {
      String type = block.path("type").asText();
      if ("text".equals(type) && !block.path("text").asText("").isBlank()) {
        if (!text.isEmpty()) text.append("\n");
        text.append(block.path("text").asText());
      } else if ("tool_use".equals(type)) {
        toolUses++;
      } else if ("thinking".equals(type)) {
        hasThinking = true;
      }
    }
    if (!text.isEmpty()) return text.toString();
    if (toolUses > 0) return "[assistant requested " + toolUses + " tool call" + (toolUses == 1 ? "" : "s") + "]";
    return hasThinking ? "[assistant thinking block]" : "";
  }
}
