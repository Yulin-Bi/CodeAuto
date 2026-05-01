package com.codeauto.context;

import com.codeauto.core.ChatMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MicroCompactService {
  public static final String CLEAR_MARKER = "[Output cleared for context space]";
  private static final double MICROCOMPACT_UTILIZATION = 0.50;
  private static final int KEEP_RECENT_TOOL_RESULTS = 3;
  private static final Set<String> COMPACTABLE_TOOLS = Set.of(
      "read_file",
      "run_command",
      "search_files",
      "list_files",
      "web_fetch");

  private MicroCompactService() {
  }

  public static List<ChatMessage> microcompact(List<ChatMessage> messages, int contextWindow) {
    ContextStats stats = TokenEstimator.compute(messages, contextWindow);
    double utilization = contextWindow <= 0 ? 0 : (double) stats.estimatedTokens() / contextWindow;
    if (utilization < MICROCOMPACT_UTILIZATION) {
      return messages;
    }

    List<Integer> toolResultIndices = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      ChatMessage message = messages.get(i);
      if (message instanceof ChatMessage.ToolResultMessage result && COMPACTABLE_TOOLS.contains(result.toolName())) {
        toolResultIndices.add(i);
      }
    }

    if (toolResultIndices.size() <= KEEP_RECENT_TOOL_RESULTS) {
      return messages;
    }

    Set<Integer> clearIndices = new HashSet<>(
        toolResultIndices.subList(0, toolResultIndices.size() - KEEP_RECENT_TOOL_RESULTS));
    List<ChatMessage> compacted = new ArrayList<>(messages.size());
    boolean changed = false;
    for (int i = 0; i < messages.size(); i++) {
      ChatMessage message = messages.get(i);
      if (clearIndices.contains(i) && message instanceof ChatMessage.ToolResultMessage result) {
        if (!CLEAR_MARKER.equals(result.content())) {
          changed = true;
          compacted.add(new ChatMessage.ToolResultMessage(
              result.toolUseId(), result.toolName(), CLEAR_MARKER, result.isError()));
        } else {
          compacted.add(message);
        }
      } else {
        compacted.add(message);
      }
    }
    return changed ? compacted : messages;
  }
}
