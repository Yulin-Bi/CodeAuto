package com.codeauto;

import com.codeauto.context.CompactService;
import com.codeauto.context.MicroCompactService;
import com.codeauto.context.TokenEstimator;
import com.codeauto.context.ToolResultStorage;
import com.codeauto.core.AgentStep;
import com.codeauto.core.ChatMessage;
import com.codeauto.model.ModelAdapter;
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
  void heuristicSummaryGroupsMessagesByType() {
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("system"));
    messages.add(new ChatMessage.UserMessage("请修复 AgentLoop 中的 bug"));
    messages.add(new ChatMessage.AssistantMessage("我来分析一下问题..."));
    messages.add(new com.codeauto.core.ChatMessage.AssistantToolCallMessage(
        "id1", "read_file", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
        .put("path", "AgentLoop.java")));
    messages.add(new ChatMessage.ToolResultMessage("id1", "read_file",
        "package com.codeauto.core;\npublic class AgentLoop {\n  private volatile boolean cancelled;\n}", false));
    messages.add(new ChatMessage.AssistantMessage("找到问题了，cancelled 标志未重置"));
    messages.add(new ChatMessage.UserMessage("ok 修一下"));

    var result = CompactService.compactWithStats(messages, 2, 200_000, null, null);

    assertTrue(result.summary() != null);
    String summary = result.summary().content();
    assertTrue(summary.contains("### User Requests"), "should have User Requests section");
    assertTrue(summary.contains("### Tools Called"), "should have Tools Called section");
    assertTrue(summary.contains("read_file"), "should mention tool name");
    assertTrue(summary.contains("### Key Outputs"), "should have Key Outputs section");
    assertTrue(summary.contains("### Assistant Responses"), "should have Assistant Responses section");
    assertTrue(!summary.contains("### AssistantRawMessage"), "should not leak internal type names");
  }

  @Test
  void modelAssistedSummaryUsesModelOutput() throws Exception {
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("system"));
    messages.add(new ChatMessage.UserMessage("hello"));
    messages.add(new ChatMessage.AssistantMessage("hi there"));
    messages.add(new ChatMessage.UserMessage("bye"));
    messages.add(new ChatMessage.AssistantMessage("goodbye"));

    ModelAdapter model = msgs -> new AgentStep.AssistantStep(
        "### User Intent\ntest conversation\n\n### Key Decisions\nNone.",
        AgentStep.Kind.FINAL, null);

    var result = CompactService.compactWithStats(messages, 2, 200_000, null, model);

    assertTrue(result.summary() != null);
    String summary = result.summary().content();
    assertTrue(summary.contains("### User Intent"), "should use model output: " + summary);
    assertTrue(summary.contains("### Key Decisions"), "should use model output: " + summary);
  }

  @Test
  void fallsBackToHeuristicWhenModelThrows() {
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("system"));
    messages.add(new ChatMessage.UserMessage("hello"));
    messages.add(new ChatMessage.AssistantMessage("world"));
    messages.add(new ChatMessage.UserMessage("final"));

    ModelAdapter failingModel = msgs -> { throw new RuntimeException("summarization failed"); };

    var result = CompactService.compactWithStats(messages, 2, 200_000, null, failingModel);

    assertTrue(result.summary() != null);
    String summary = result.summary().content();
    assertTrue(summary.contains("### User Requests"), "should fall back to heuristic: " + summary);
  }

  @Test
  void fallsBackToHeuristicWhenModelReturnsToolCalls() {
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("system"));
    messages.add(new ChatMessage.UserMessage("hello"));
    messages.add(new ChatMessage.AssistantMessage("world"));
    messages.add(new ChatMessage.UserMessage("final"));

    // Model returns tool calls instead of text — should trigger fallback
    ModelAdapter toolCallModel = msgs -> new AgentStep.ToolCallsStep(
        List.of(new com.codeauto.core.ToolCall("id1", "read_file",
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path", "test.txt"))),
        null, AgentStep.ContentKind.ASSISTANT, null);

    var result = CompactService.compactWithStats(messages, 2, 200_000, null, toolCallModel);

    assertTrue(result.summary() != null);
    String summary = result.summary().content();
    assertTrue(summary.contains("### User Requests"), "should fall back to heuristic: " + summary);
  }

  @Test
  void compactionSavesArtifactWhenCwdProvided() throws Exception {
    java.nio.file.Path cwd = Files.createTempDirectory("codeauto-compact-artifact");
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("system"));
    messages.add(new ChatMessage.UserMessage("hello"));
    messages.add(new ChatMessage.AssistantMessage("world"));
    messages.add(new ChatMessage.UserMessage("final"));

    var result = CompactService.compactWithStats(messages, 2, 200_000, cwd, null);

    assertTrue(result.summary() != null);
    String summary = result.summary().content();
    assertTrue(summary.contains(".codeauto/compacted/compact-"), "should reference artifact path");
    java.nio.file.Path compactedDir = cwd.resolve(".codeauto").resolve("compacted");
    assertTrue(Files.exists(compactedDir), "compacted dir should exist");
    assertTrue(Files.list(compactedDir).findAny().isPresent(), "should have at least one artifact file");
  }

  @Test
  void compactionPreservesContextSummaryAsBoundary() {
    // Messages with a previous ContextSummaryMessage at index 1.
    // Only messages AFTER the boundary should be compressed.
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("system"));
    messages.add(new ChatMessage.ContextSummaryMessage("old compaction summary", 50, 1000L));
    for (int i = 0; i < 12; i++) {
      messages.add(new ChatMessage.UserMessage("message " + i));
    }

    var result = CompactService.compactWithStats(messages, 4, 200_000, null, null);

    // Old ContextSummaryMessage must be preserved exactly at the same position.
    assertTrue(result.messages().get(1) instanceof ChatMessage.ContextSummaryMessage);
    ChatMessage.ContextSummaryMessage oldSummary =
        (ChatMessage.ContextSummaryMessage) result.messages().get(1);
    assertEquals("old compaction summary", oldSummary.content());
    assertEquals(50, oldSummary.compressedCount());
    // New ContextSummaryMessage must follow.
    assertTrue(result.messages().get(2) instanceof ChatMessage.ContextSummaryMessage);
    // Tail messages must be present.
    assertTrue(result.messages().size() > 3);
  }

  @Test
  void compactionOnlyCompressesMessagesAfterLastSummary() {
    // Scenario: after first compaction, new messages arrive. Second compaction
    // should only compress the new messages, leaving the first summary untouched.
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("system"));
    // First compaction's summary (50 old messages → summary)
    messages.add(new ChatMessage.ContextSummaryMessage("first compaction result", 50, 1000L));
    // Old tail messages from first compaction + new messages from second turn
    messages.add(new ChatMessage.UserMessage("old tail A"));
    messages.add(new ChatMessage.AssistantMessage("old tail B"));
    messages.add(new ChatMessage.UserMessage("new turn request"));
    messages.add(new ChatMessage.AssistantMessage("new turn response"));
    messages.add(new ChatMessage.UserMessage("another request"));
    messages.add(new ChatMessage.AssistantToolCallMessage("id1", "read_file",
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path", "test.txt")));
    messages.add(new ChatMessage.ToolResultMessage("id1", "read_file", "file content here", false));
    messages.add(new ChatMessage.AssistantMessage("final response"));

    var result = CompactService.compactWithStats(messages, 3, 200_000, null, null);

    // First summary preserved as-is.
    assertTrue(result.messages().get(1) instanceof ChatMessage.ContextSummaryMessage);
    ChatMessage.ContextSummaryMessage firstSummary =
        (ChatMessage.ContextSummaryMessage) result.messages().get(1);
    assertEquals("first compaction result", firstSummary.content());
    assertEquals(50, firstSummary.compressedCount());
    // Second summary is new, only covers messages after the first summary.
    assertTrue(result.messages().get(2) instanceof ChatMessage.ContextSummaryMessage);
    ChatMessage.ContextSummaryMessage secondSummary =
        (ChatMessage.ContextSummaryMessage) result.messages().get(2);
    assertTrue(secondSummary.compressedCount() < 10,
        "should only compress new messages, not re-compress old tail");
    // The second summary should reference the new turn content, not the first summary.
    assertTrue(secondSummary.content().contains("new turn"),
        "new summary should describe messages after boundary");
  }

  @Test
  void compactionSkipsWhenNothingToCompressAfterBoundary() {
    // Everything after the ContextSummaryMessage fits in the tail — no compression needed.
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage.SystemMessage("system"));
    messages.add(new ChatMessage.ContextSummaryMessage("recent compaction", 30, 2000L));
    messages.add(new ChatMessage.UserMessage("msg A"));
    messages.add(new ChatMessage.UserMessage("msg B"));
    // 4 msgs, compactFrom=2, 4-2=2 <= keepTail=2 → nothing to compress.

    var result = CompactService.compactWithStats(messages, 2, 200_000, null, null);

    // No new summary — nothing to compress beyond the boundary.
    assertTrue(result.summary() == null);
    assertEquals(4, result.messages().size());
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
