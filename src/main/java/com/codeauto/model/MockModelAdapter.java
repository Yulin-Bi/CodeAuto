package com.codeauto.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.core.AgentStep;
import com.codeauto.core.ChatMessage;
import com.codeauto.core.ToolCall;
import java.util.List;
import java.util.UUID;

public class MockModelAdapter implements ModelAdapter {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public AgentStep next(List<ChatMessage> messages) {
    boolean hasToolResult = messages.stream().anyMatch(ChatMessage.ToolResultMessage.class::isInstance);
    if (hasToolResult) {
      ChatMessage.ToolResultMessage latest = messages.stream()
          .filter(ChatMessage.ToolResultMessage.class::isInstance)
          .map(ChatMessage.ToolResultMessage.class::cast)
          .reduce((first, second) -> second)
          .orElseThrow();
      return new AgentStep.AssistantStep("Mock complete. Last tool result:\n" + latest.content(), AgentStep.Kind.FINAL, null);
    }

    String lastUser = messages.stream()
        .filter(ChatMessage.UserMessage.class::isInstance)
        .map(ChatMessage.UserMessage.class::cast)
        .map(ChatMessage.UserMessage::content)
        .reduce((first, second) -> second)
        .orElse("");

    if (lastUser.toLowerCase().contains("list")) {
      return new AgentStep.ToolCallsStep(
          List.of(new ToolCall(UUID.randomUUID().toString(), "list_files", MAPPER.createObjectNode())),
          "I will inspect the workspace.",
          AgentStep.ContentKind.PROGRESS,
          null);
    }

    if (lastUser.toLowerCase().startsWith("read ")) {
      String path = lastUser.substring("read ".length()).trim();
      return new AgentStep.ToolCallsStep(
          List.of(new ToolCall(UUID.randomUUID().toString(), "read_file", MAPPER.createObjectNode().put("path", path))),
          "I will read " + path + ".",
          AgentStep.ContentKind.PROGRESS,
          null);
    }

    if (lastUser.toLowerCase().startsWith("grep ")) {
      String pattern = lastUser.substring("grep ".length()).trim();
      return new AgentStep.ToolCallsStep(
          List.of(new ToolCall(UUID.randomUUID().toString(), "grep_files", MAPPER.createObjectNode().put("pattern", pattern))),
          "I will search for " + pattern + ".",
          AgentStep.ContentKind.PROGRESS,
          null);
    }

    if (lastUser.toLowerCase().startsWith("run ")) {
      String command = lastUser.substring("run ".length()).trim();
      return new AgentStep.ToolCallsStep(
          List.of(new ToolCall(UUID.randomUUID().toString(), "run_command", MAPPER.createObjectNode().put("command", command))),
          "I will run the command.",
          AgentStep.ContentKind.PROGRESS,
          null);
    }

    if (lastUser.toLowerCase().startsWith("background ")) {
      String command = lastUser.substring("background ".length()).trim();
      return new AgentStep.ToolCallsStep(
          List.of(new ToolCall(UUID.randomUUID().toString(), "run_command",
              MAPPER.createObjectNode().put("command", command).put("background", true))),
          "I will start the command in the background.",
          AgentStep.ContentKind.PROGRESS,
          null);
    }

    if (lastUser.toLowerCase().contains("background tasks")) {
      return new AgentStep.ToolCallsStep(
          List.of(new ToolCall(UUID.randomUUID().toString(), "background_tasks", MAPPER.createObjectNode())),
          "I will list background tasks.",
          AgentStep.ContentKind.PROGRESS,
          null);
    }

    return new AgentStep.AssistantStep("Mock response: " + lastUser, AgentStep.Kind.FINAL, null);
  }
}
