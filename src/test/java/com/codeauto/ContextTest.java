package com.codeauto;

import com.codeauto.context.CompactService;
import com.codeauto.context.MicroCompactService;
import com.codeauto.context.TokenEstimator;
import com.codeauto.context.ToolResultStorage;
import com.codeauto.core.ChatMessage;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextTest {
  @Test
  void computesEstimatedContextStats() {
    var stats = TokenEstimator.compute(List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("hello world")), 1000);

    assertEquals(2, stats.messageCount());
    assertTrue(stats.estimatedTokens() > 0);
  }

  @Test
  void compactsMiddleMessagesAndKeepsSystemAndTail() {
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("system"));
    for (int i = 0; i < 12; i++) {
      messages.add(new ChatMessage.UserMessage("message " + i));
    }

    List<ChatMessage> compacted = CompactService.compact(messages, 4);

    assertEquals("system", compacted.getFirst().role());
    assertTrue(compacted.get(1) instanceof ChatMessage.ContextSummaryMessage);
    assertEquals(6, compacted.size());
  }

  @Test
  void largeToolResultsAreStoredAndReplaced() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-tool-results-home");
    System.setProperty("codeauto.home", home.toString());
    String content = "x".repeat(100);
    ToolResultStorage storage = new ToolResultStorage(20, 10);

    ChatMessage.ToolResultMessage result = storage.replaceIfLarge(
        new ChatMessage.ToolResultMessage("id", "tool", content, false));

    assertTrue(result.content().contains("Large tool result"));
    assertTrue(Files.list(home.resolve("tool-results")).findAny().isPresent());
  }

  @Test
  void toolResultBatchBudgetPersistsLargestFreshResults() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-tool-results-budget-home");
    System.setProperty("codeauto.home", home.toString());
    ToolResultStorage storage = new ToolResultStorage(10_000, 20, 6_000);

    List<ChatMessage.ToolResultMessage> results = storage.applyBatchBudget(List.of(
        new ChatMessage.ToolResultMessage("a", "tool", "a".repeat(5_000), false),
        new ChatMessage.ToolResultMessage("b", "tool", "b".repeat(5_000), false)));

    assertTrue(results.getFirst().content().contains("Large tool result"));
    assertEquals("b".repeat(5_000), results.get(1).content());
    assertTrue(Files.list(home.resolve("tool-results")).findAny().isPresent());
  }

  @Test
  void microcompactClearsOldCompactableToolResultsWhenContextIsLarge() {
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("system"));
    for (int i = 0; i < 5; i++) {
      messages.add(new ChatMessage.ToolResultMessage("id-" + i, "read_file", "x".repeat(1_000), false));
    }

    List<ChatMessage> compacted = MicroCompactService.microcompact(messages, 1_000);

    assertEquals(MicroCompactService.CLEAR_MARKER, ((ChatMessage.ToolResultMessage) compacted.get(1)).content());
    assertEquals(MicroCompactService.CLEAR_MARKER, ((ChatMessage.ToolResultMessage) compacted.get(2)).content());
    assertEquals("x".repeat(1_000), ((ChatMessage.ToolResultMessage) compacted.get(5)).content());
  }
}
