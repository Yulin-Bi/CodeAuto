package com.codeauto;

import com.codeauto.core.AgentLoop;
import com.codeauto.core.AgentLoopListener;
import com.codeauto.core.AgentStep;
import com.codeauto.core.ChatMessage;
import com.codeauto.model.ModelAdapter;
import com.codeauto.model.MockModelAdapter;
import com.codeauto.permissions.PermissionManager;
import com.codeauto.tool.ToolContext;
import com.codeauto.core.ToolCall;
import com.codeauto.tools.DefaultTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void mockModelCanCallToolAndReturnFinalAnswer() throws Exception {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    AgentLoop loop = new AgentLoop(
        new MockModelAdapter(),
        DefaultTools.create(),
        new ToolContext(cwd, new PermissionManager(cwd)),
        8);
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("test"));
    messages.add(new ChatMessage.UserMessage("please list files"));

    List<ChatMessage> result = loop.runTurn(messages);

    assertTrue(result.stream().anyMatch(ChatMessage.AssistantToolCallMessage.class::isInstance));
    assertTrue(result.stream().anyMatch(ChatMessage.ToolResultMessage.class::isInstance));
    assertTrue(result.stream()
        .filter(ChatMessage.AssistantMessage.class::isInstance)
        .map(ChatMessage.AssistantMessage.class::cast)
        .anyMatch(message -> message.content().contains("Mock complete")));
  }

  @Test
  void autoCompactsBeforeModelCallWhenContextIsCritical() throws Exception {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<List<ChatMessage>> observedCalls = new ArrayList<>();
    List<String> events = new ArrayList<>();
    ModelAdapter model = messages -> {
      observedCalls.add(messages);
      return new AgentStep.AssistantStep("done", AgentStep.Kind.FINAL, null);
    };
    AgentLoop loop = new AgentLoop(
        model,
        DefaultTools.create(),
        new ToolContext(cwd, new PermissionManager(cwd)),
        8,
        new AgentLoopListener() {
          @Override
          public void onAutoCompact(com.codeauto.context.CompactService.CompactResult result) {
            events.add("compact:" + result.removedCount());
          }
        },
        1_000);
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("test"));
    for (int i = 0; i < 20; i++) {
      messages.add(new ChatMessage.UserMessage("message " + i + " " + "x".repeat(400)));
    }

    List<ChatMessage> result = loop.runTurn(messages);

    assertTrue(events.stream().anyMatch(event -> event.startsWith("compact:")));
    assertTrue(result.stream().anyMatch(ChatMessage.ContextSummaryMessage.class::isInstance));
    assertTrue(observedCalls.getFirst().stream().anyMatch(ChatMessage.ContextSummaryMessage.class::isInstance));
  }

  @Test
  void listenerReceivesProgressToolAndAssistantEvents() throws Exception {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<String> events = new ArrayList<>();
    AgentLoop loop = new AgentLoop(
        new MockModelAdapter(),
        DefaultTools.create(),
        new ToolContext(cwd, new PermissionManager(cwd)),
        8,
        new AgentLoopListener() {
          @Override public void onProgressMessage(String content) { events.add("progress"); }
          @Override public void onToolStart(String toolName, com.fasterxml.jackson.databind.JsonNode input) {
            events.add("start:" + toolName);
          }
          @Override public void onToolResult(String toolName, String output, boolean isError) {
            events.add("result:" + toolName + ":" + isError);
          }
          @Override public void onAssistantMessage(String content) { events.add("assistant"); }
        },
        200_000);
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("test"));
    messages.add(new ChatMessage.UserMessage("please list files"));

    loop.runTurn(messages);

    assertTrue(events.contains("progress"));
    assertTrue(events.contains("start:list_files"));
    assertTrue(events.contains("result:list_files:false"));
    assertTrue(events.contains("assistant"));
  }

  @Test
  void listenerReceivesAssistantStreamingDeltas() throws Exception {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<String> events = new ArrayList<>();
    ModelAdapter model = new ModelAdapter() {
      @Override
      public AgentStep next(List<ChatMessage> messages) {
        throw new AssertionError("streaming path should be used");
      }

      @Override
      public AgentStep next(List<ChatMessage> messages, AgentLoopListener listener) {
        listener.onAssistantDelta("he");
        listener.onAssistantDelta("llo");
        return new AgentStep.AssistantStep("hello", AgentStep.Kind.FINAL, null);
      }
    };
    AgentLoop loop = new AgentLoop(
        model,
        DefaultTools.create(),
        new ToolContext(cwd, new PermissionManager(cwd)),
        8,
        new AgentLoopListener() {
          @Override public void onAssistantDelta(String delta) { events.add("delta:" + delta); }
          @Override public void onAssistantMessage(String content) { events.add("assistant:" + content); }
        },
        200_000);
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("test"));
    messages.add(new ChatMessage.UserMessage("say hello"));

    List<ChatMessage> result = loop.runTurn(messages);

    assertTrue(events.contains("delta:he"));
    assertTrue(events.contains("delta:llo"));
    assertTrue(events.contains("assistant:hello"));
    assertTrue(result.stream().anyMatch(message ->
        message instanceof ChatMessage.AssistantMessage assistant && assistant.content().equals("hello")));
  }

  @Test
  void preservesRawAssistantToolCallContentForProviderRoundTrip() throws Exception {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    var rawContent = MAPPER.createArrayNode();
    rawContent.addObject()
        .put("type", "thinking")
        .put("thinking", "I should create a file.")
        .put("signature", "test-signature");
    rawContent.addObject()
        .put("type", "tool_use")
        .put("id", "toolu_1")
        .put("name", "list_files")
        .set("input", MAPPER.createObjectNode());
    ModelAdapter model = messages -> {
      if (messages.stream().anyMatch(ChatMessage.ToolResultMessage.class::isInstance)) {
        return new AgentStep.AssistantStep("done", AgentStep.Kind.FINAL, null);
      }
      return new AgentStep.ToolCallsStep(
          List.of(new ToolCall("toolu_1", "list_files", MAPPER.createObjectNode())),
          "",
          null,
          null,
          rawContent);
    };
    AgentLoop loop = new AgentLoop(
        model,
        DefaultTools.create(),
        new ToolContext(cwd, new PermissionManager(cwd)),
        8);
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("test"));
    messages.add(new ChatMessage.UserMessage("create docs"));

    List<ChatMessage> result = loop.runTurn(messages);

    assertTrue(result.stream().anyMatch(message ->
        message instanceof ChatMessage.AssistantRawMessage raw && raw.content().equals(rawContent)));
  }
}
