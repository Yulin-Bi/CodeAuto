package com.codeauto.core;

import com.codeauto.context.MicroCompactService;
import com.codeauto.context.CompactService;
import com.codeauto.context.ContextStats;
import com.codeauto.context.TokenEstimator;
import com.codeauto.context.ToolResultStorage;
import com.codeauto.model.ModelAdapter;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolRegistry;
import com.codeauto.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;

public class AgentLoop {
  private static final int DEFAULT_CONTEXT_WINDOW = 200_000;
  private static final int DEFAULT_COMPACT_TAIL_MESSAGES = 8;
  private final ModelAdapter model;
  private final ToolRegistry tools;
  private final ToolContext toolContext;
  private final int maxSteps;
  private final ToolResultStorage toolResultStorage;
  private final AgentLoopListener listener;
  private final int contextWindow;

  public AgentLoop(ModelAdapter model, ToolRegistry tools, ToolContext toolContext, int maxSteps) {
    this(model, tools, toolContext, maxSteps, AgentLoopListener.NOOP, DEFAULT_CONTEXT_WINDOW);
  }

  public AgentLoop(
      ModelAdapter model,
      ToolRegistry tools,
      ToolContext toolContext,
      int maxSteps,
      AgentLoopListener listener,
      int contextWindow
  ) {
    this.model = model;
    this.tools = tools;
    this.toolContext = toolContext;
    this.maxSteps = maxSteps;
    this.toolResultStorage = new ToolResultStorage();
    this.listener = listener == null ? AgentLoopListener.NOOP : listener;
    this.contextWindow = contextWindow <= 0 ? DEFAULT_CONTEXT_WINDOW : contextWindow;
  }

  public List<ChatMessage> runTurn(List<ChatMessage> initialMessages) throws Exception {
    List<ChatMessage> messages = new ArrayList<>(initialMessages);
    int emptyRetries = 0;

    for (int step = 0; step < maxSteps; step++) {
      messages = new ArrayList<>(MicroCompactService.microcompact(messages, contextWindow));
      ContextStats stats = TokenEstimator.compute(messages, contextWindow);
      listener.onContextStats(stats);
      if (step == 0 && ("critical".equals(stats.warningLevel()) || "blocked".equals(stats.warningLevel()))) {
        CompactService.CompactResult compact = CompactService.compactWithStats(
            messages, DEFAULT_COMPACT_TAIL_MESSAGES, contextWindow);
        if (compact.summary() != null) {
          messages = new ArrayList<>(compact.messages());
          listener.onAutoCompact(compact);
          listener.onContextStats(TokenEstimator.compute(messages, contextWindow));
        }
      }
      AgentStep next = model.next(List.copyOf(messages));

      if (next instanceof AgentStep.AssistantStep assistant) {
        String content = assistant.content() == null ? "" : assistant.content().trim();
        if (content.isEmpty() && emptyRetries < 2) {
          emptyRetries++;
          messages.add(new ChatMessage.UserMessage("Your last response was empty. Continue immediately."));
          continue;
        }
        if (content.isEmpty()) {
          messages.add(new ChatMessage.AssistantMessage("Model returned an empty response.", assistant.usage(), false));
          return messages;
        }
        if (assistant.kind() == AgentStep.Kind.PROGRESS) {
          listener.onProgressMessage(content);
          messages.add(new ChatMessage.AssistantProgressMessage(content, assistant.usage()));
          messages.add(new ChatMessage.UserMessage("Continue from your progress update with the next concrete step."));
          continue;
        }
        listener.onAssistantMessage(content);
        messages.add(new ChatMessage.AssistantMessage(content, assistant.usage(), false));
        return messages;
      }

      AgentStep.ToolCallsStep toolCalls = (AgentStep.ToolCallsStep) next;
      if (toolCalls.content() != null && !toolCalls.content().isBlank()) {
        if (toolCalls.contentKind() == AgentStep.ContentKind.PROGRESS) {
          listener.onProgressMessage(toolCalls.content());
          if (toolCalls.rawContent() == null) {
            messages.add(new ChatMessage.AssistantProgressMessage(toolCalls.content(), toolCalls.usage()));
          }
        } else {
          listener.onAssistantMessage(toolCalls.content());
          if (toolCalls.rawContent() == null) {
            messages.add(new ChatMessage.AssistantMessage(toolCalls.content(), toolCalls.usage(), false));
          }
        }
      }
      if (toolCalls.calls().isEmpty() && toolCalls.contentKind() != AgentStep.ContentKind.PROGRESS) {
        return messages;
      }

      List<ExecutedToolResult> executed = new ArrayList<>();
      List<ChatMessage.ToolResultMessage> pendingResults = new ArrayList<>();
      for (ToolCall call : toolCalls.calls()) {
        listener.onToolStart(call.toolName(), call.input());
        ToolResult result = tools.execute(call.toolName(), call.input(), toolContext);
        listener.onToolResult(call.toolName(), result.output(), !result.ok());
        ChatMessage.ToolResultMessage toolResult =
            new ChatMessage.ToolResultMessage(call.id(), call.toolName(), result.output(), !result.ok());
        executed.add(new ExecutedToolResult(call, result));
        pendingResults.add(toolResult);
      }

      List<ChatMessage.ToolResultMessage> budgetedResults = toolResultStorage.applyBatchBudget(pendingResults);
      if (toolCalls.rawContent() != null) {
        messages.add(new ChatMessage.AssistantRawMessage(toolCalls.rawContent(), toolCalls.usage()));
        messages.addAll(budgetedResults);
        continue;
      }
      for (int i = 0; i < executed.size(); i++) {
        ExecutedToolResult entry = executed.get(i);
        ChatMessage.ToolResultMessage toolResult = budgetedResults.get(i);
        messages.add(new ChatMessage.AssistantToolCallMessage(
            entry.call().id(),
            entry.call().toolName(),
            entry.call().input(),
            i == executed.size() - 1 ? toolCalls.usage() : null));
        messages.add(toolResult);
        if (entry.result().awaitUser()) {
          messages.add(new ChatMessage.AssistantMessage(entry.result().output()));
          return messages;
        }
      }
    }

    messages.add(new ChatMessage.AssistantMessage("Reached maximum tool step limit; stopped current turn."));
    return messages;
  }

  private record ExecutedToolResult(ToolCall call, ToolResult result) {
  }
}
