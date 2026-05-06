package com.codeauto;

import com.codeauto.core.AgentStep;
import com.codeauto.core.ChatMessage;
import com.codeauto.curator.Curator;
import com.codeauto.curator.Curator.BulletDelta;
import com.codeauto.memory.MemoryEntry;
import com.codeauto.memory.MemoryManager;
import com.codeauto.model.ModelAdapter;
import com.codeauto.reflection.ReflectionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionServiceTest {

  @Test
  void detectsToolErrorTrigger() {
    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("fix the bug"),
        new ChatMessage.AssistantMessage("let me fix it"),
        new ChatMessage.ToolResultMessage("call1", "run_command", "error output", true));

    assertEquals(ReflectionService.ReflectionTrigger.TOOL_ERROR,
        ReflectionService.detectTrigger(messages));
  }

  @Test
  void detectsMaxStepsTrigger() {
    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("do it"),
        new ChatMessage.AssistantMessage("Reached maximum tool step limit; stopped current turn."));

    assertEquals(ReflectionService.ReflectionTrigger.MAX_STEPS,
        ReflectionService.detectTrigger(messages));
  }

  @Test
  void detectsCancelledTrigger() {
    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("do it"),
        new ChatMessage.AssistantMessage("(Interrupted)"));

    assertEquals(ReflectionService.ReflectionTrigger.CANCELLED,
        ReflectionService.detectTrigger(messages));
  }

  @Test
  void detectsUserDissatisfaction() {
    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("fix the bug"),
        new ChatMessage.AssistantMessage("done"),
        new ChatMessage.UserMessage("that's wrong, try again"));

    assertEquals(ReflectionService.ReflectionTrigger.USER_DISSATISFACTION,
        ReflectionService.detectTrigger(messages));
  }

  @Test
  void noTriggerForNormalTurn() {
    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("what is 2+2"),
        new ChatMessage.AssistantMessage("4"),
        new ChatMessage.ToolResultMessage("call1", "read_file", "file content", false));

    assertEquals(ReflectionService.ReflectionTrigger.NONE,
        ReflectionService.detectTrigger(messages));
  }

  @Test
  void generatesReflectionAndSavesMemory() throws Exception {
    Path home = Files.createTempDirectory("codeauto-reflection-test");
    System.setProperty("codeauto.home", home.toString());
    try {
      ModelAdapter mockModel = messages ->
          new AgentStep.AssistantStep("### What Went Wrong\nThe test failed.", AgentStep.Kind.FINAL, null);

      List<ChatMessage> messages = List.of(
          new ChatMessage.SystemMessage("system"),
          new ChatMessage.UserMessage("fix the bug"),
          new ChatMessage.AssistantMessage("trying"),
          new ChatMessage.ToolResultMessage("call1", "run_command", "BUILD FAILURE", true));

      Optional<MemoryEntry> result = ReflectionService.reflectIfNeeded(messages, mockModel, null);
      assertTrue(result.isPresent());
      MemoryEntry entry = result.get();
      assertEquals("Reflection on tool errors", entry.title());
      assertTrue(entry.content().contains("What Went Wrong"));
      assertTrue(entry.tags().contains("reflection"));
      assertTrue(entry.tags().contains("auto"));

      MemoryManager reflectionManager = new MemoryManager(home.resolve("reflections"));
      reflectionManager.delete(entry.id());
    } finally {
      System.clearProperty("codeauto.home");
    }
  }

  @Test
  void skipsReflectionWhenModelFails() {
    ModelAdapter failingModel = messages -> {
      throw new RuntimeException("model failure");
    };

    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("fix the bug"),
        new ChatMessage.ToolResultMessage("call1", "run_command", "error", true));

    Optional<MemoryEntry> result = ReflectionService.reflectIfNeeded(messages, failingModel, null);
    assertTrue(result.isEmpty());
  }

  @Test
  void skipsReflectionWhenNoModel() {
    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("fix the bug"),
        new ChatMessage.ToolResultMessage("call1", "run_command", "error", true));

    Optional<MemoryEntry> result = ReflectionService.reflectIfNeeded(messages, null, null);
    assertTrue(result.isEmpty());
  }

  @Test
  void skipsReflectionWhenModelReturnsToolCalls() {
    ModelAdapter toolModel = messages ->
        new AgentStep.ToolCallsStep(List.of(), "text", AgentStep.ContentKind.ASSISTANT, null, null);

    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("fix the bug"),
        new ChatMessage.ToolResultMessage("call1", "run_command", "error", true));

    Optional<MemoryEntry> result = ReflectionService.reflectIfNeeded(messages, toolModel, null);
    assertTrue(result.isEmpty());
  }

  @Test
  void skipsWhenMessagesEmpty() {
    ModelAdapter mockModel = messages ->
        new AgentStep.AssistantStep("reflection", AgentStep.Kind.FINAL, null);

    Optional<MemoryEntry> result = ReflectionService.reflectIfNeeded(List.of(), mockModel, null);
    assertTrue(result.isEmpty());
  }

  @Test
  void reflectionFeedsReusableLessonIntoCuratorBullet() throws Exception {
    Path home = Files.createTempDirectory("codeauto-pipeline-test");
    System.setProperty("codeauto.home", home.toString());
    try {
      String reflectionText = ""
          + "### What Went Wrong\nTest failed due to missing import.\n\n"
          + "### Root Cause\nDid not check existing imports.\n\n"
          + "### What Should Have Been Done Differently\nGrep for imports first.\n\n"
          + "### Reusable Lesson\n"
          + "Always grep for existing imports before adding a new class reference.\n";

      ModelAdapter mockModel = messages ->
          new AgentStep.AssistantStep(reflectionText, AgentStep.Kind.FINAL, null);

      List<ChatMessage> messages = List.of(
          new ChatMessage.SystemMessage("system"),
          new ChatMessage.UserMessage("add new feature"),
          new ChatMessage.AssistantMessage("let me add it"),
          new ChatMessage.ToolResultMessage("call1", "mvn", "COMPILATION ERROR", true));

      Optional<MemoryEntry> result = ReflectionService.reflectIfNeeded(messages, mockModel, null);
      assertTrue(result.isPresent());

      // FEEDBACK memory saved in home/reflections/, bullet in home/bullets/
      MemoryManager reflectionManager = new MemoryManager(home.resolve("reflections"));
      MemoryManager bulletManager = new MemoryManager(home.resolve("bullets"));

      // Verify FEEDBACK memory was saved
      MemoryEntry reflectionEntry = result.get();
      assertEquals("Reflection on tool errors", reflectionEntry.title());
      assertTrue(reflectionEntry.content().contains("Reusable Lesson"));

      // Verify Curator created a bullet from the reusable lesson
      var bullets = bulletManager.list().stream()
          .filter(e -> e.isBullet() && e.content().contains("grep for existing imports"))
          .toList();
      assertEquals(1, bullets.size());
      MemoryEntry bullet = bullets.getFirst();
      assertEquals("common_mistakes", bullet.section());
      assertTrue(bullet.tags().contains("reflection"));
      assertTrue(bullet.tags().contains("auto"));

      reflectionManager.delete(reflectionEntry.id());
      bulletManager.delete(bullet.id());
    } finally {
      System.clearProperty("codeauto.home");
    }
  }

  @Test
  void extractsCitationsFromAssistantMessages() {
    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.AssistantMessage("I'll use [bullet:tip-abc] to guide this fix."),
        new ChatMessage.AssistantMessage("Also referencing [bullet:tip-xyz] for safety."));

    Set<String> cited = ReflectionService.extractCitations(messages);
    assertEquals(2, cited.size());
    assertTrue(cited.contains("tip-abc"));
    assertTrue(cited.contains("tip-xyz"));
  }

  @Test
  void extractsNoCitationsWhenNonePresent() {
    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.AssistantMessage("Let me fix this bug."));

    Set<String> cited = ReflectionService.extractCitations(messages);
    assertTrue(cited.isEmpty());
  }

  @Test
  void parsesBulletTagsFromReflectionText() {
    String reflection = ""
        + "### What Went Wrong\nCompilation error.\n\n"
        + "### Bullet Tags\n"
        + "[bullet:tip-abc]: harmful\n"
        + "[bullet:tip-xyz]: helpful\n";

    Map<String, String> tags = ReflectionService.parseBulletTags(reflection);
    assertEquals(2, tags.size());
    assertEquals("harmful", tags.get("tip-abc"));
    assertEquals("helpful", tags.get("tip-xyz"));
  }

  @Test
  void bulletTaggingLoopIncrementsCounters() throws Exception {
    Path home = Files.createTempDirectory("codeauto-bullet-tag-test");
    System.setProperty("codeauto.home", home.toString());
    try {
      // Bullets now live in home/bullets/ — align with ReflectionService's new layout
      java.nio.file.Path bulletsDir = home.resolve("bullets");
      MemoryManager bulletManager = new MemoryManager(bulletsDir);
      Curator curator = new Curator(bulletManager);

      // Pre-create a bullet that will be cited
      curator.applyDeltas(null, List.of(
          new BulletDelta.Add("tip-run", "Run tests after edit",
              "Always run mvn test after making changes.", "common_mistakes",
              List.of("reflection", "auto"))));

      assertEquals(0, curator.getPlaybook(null).getFirst().helpfulCount());

      // Model cites the bullet but the turn fails — model tags it harmful
      String reflectionWithTags = ""
          + "### What Went Wrong\nForgot to run tests after edit.\n\n"
          + "### Root Cause\nSkipped test verification step.\n\n"
          + "### What Should Have Been Done Differently\nRun mvn test before declaring done.\n\n"
          + "### Reusable Lesson\nAlways run the test suite after code changes.\n\n"
          + "### Bullet Tags\n"
          + "[bullet:tip-run]: harmful\n";

      ModelAdapter mockModel = messages ->
          new AgentStep.AssistantStep(reflectionWithTags, AgentStep.Kind.FINAL, null);

      // Assistant message cites the bullet
      List<ChatMessage> messages = List.of(
          new ChatMessage.SystemMessage("system"),
          new ChatMessage.UserMessage("add a feature"),
          new ChatMessage.AssistantMessage("Following [bullet:tip-run] to run tests after edit."),
          new ChatMessage.ToolResultMessage("call1", "mvn", "BUILD FAILURE", true));

      Optional<MemoryEntry> result = ReflectionService.reflectIfNeeded(messages, mockModel, null);
      assertTrue(result.isPresent());

      // Verify the cited bullet got tagged harmful
      var playbook = curator.getPlaybook(null);
      var cited = playbook.stream().filter(b -> b.bulletId().equals("tip-run")).findFirst();
      assertTrue(cited.isPresent());
      assertEquals(1, cited.get().harmfulCount());
      assertEquals(0, cited.get().helpfulCount());

      // Cleanup
      MemoryManager reflectionManager = new MemoryManager(home.resolve("reflections"));
      reflectionManager.delete(result.get().id());
      bulletManager.delete(cited.get().id());
    } finally {
      System.clearProperty("codeauto.home");
    }
  }

  /**
   * Full pipeline test: tool error → FEEDBACK memory saved → bullet created in playbook.
   * Verifies the bullet can be rediscovered via Curator.getPlaybook().
   */
  @Test
  void toolErrorCreatesMemoryAndBulletInPlaybook() throws Exception {
    Path home = Files.createTempDirectory("codeauto-e2e-pipeline");
    System.setProperty("codeauto.home", home.toString());
    try {
      String reflectionText = ""
          + "### What Went Wrong\n"
          + "mvn test failed with compilation error.\n\n"
          + "### Root Cause\n"
          + "Added a new class without importing it.\n\n"
          + "### What Should Have Been Done Differently\n"
          + "Check existing imports before adding new class references.\n\n"
          + "### Reusable Lesson\n"
          + "Before using a new class, grep the project for existing import patterns and match them.\n";

      ModelAdapter mockModel = messages ->
          new AgentStep.AssistantStep(reflectionText, AgentStep.Kind.FINAL, null);

      // Simulate a turn where a tool error occurred
      List<ChatMessage> messages = List.of(
          new ChatMessage.SystemMessage("system"),
          new ChatMessage.UserMessage("add the new feature"),
          new ChatMessage.AssistantMessage("I'll add the class now."),
          new ChatMessage.ToolResultMessage("call-1", "mvn", "COMPILATION ERROR", true));

      // Step 1: Trigger reflection — should detect TOOL_ERROR
      assertEquals(ReflectionService.ReflectionTrigger.TOOL_ERROR,
          ReflectionService.detectTrigger(messages));

      // Step 2: reflectIfNeeded creates FEEDBACK memory AND a bullet
      Optional<MemoryEntry> result = ReflectionService.reflectIfNeeded(messages, mockModel, null);
      assertTrue(result.isPresent(), "FEEDBACK memory should be saved after tool error");

      MemoryEntry feedback = result.get();
      assertEquals("Reflection on tool errors", feedback.title());
      assertTrue(feedback.content().contains("Reusable Lesson"),
          "FEEDBACK should contain the Reusable Lesson section");
      assertTrue(feedback.tags().contains("reflection"));
      assertTrue(feedback.tags().contains("auto"));
      assertFalse(feedback.isBullet(), "FEEDBACK memory itself is not a bullet");

      // Step 3: Verify the bullet was created via Curator and is in the playbook
      // Bullets are now in home/bullets/ — align with ReflectionService layout
      MemoryManager bulletManager = new MemoryManager(home.resolve("bullets"));
      Curator curator = new Curator(bulletManager);

      var playbook = curator.getPlaybook(null);
      assertFalse(playbook.isEmpty(), "Playbook should contain at least one bullet");

      // Find the bullet that was just created from the reflection
      MemoryEntry bullet = playbook.stream()
          .filter(b -> b.isBullet() && b.content().contains("grep the project for existing import"))
          .findFirst()
          .orElse(null);

      assertNotNull(bullet, "Bullet created from reflection should be in playbook");
      assertTrue(bullet.isBullet(), "Entry in playbook must be a bullet");
      assertNotNull(bullet.bulletId(), "Bullet must have a bulletId");
      assertTrue(bullet.bulletId().startsWith("ref-"), "Auto-created bullet ID should start with 'ref-'");
      assertEquals("common_mistakes", bullet.section(),
          "Tool error bullets go to common_mistakes section");
      assertTrue(bullet.tags().contains("reflection"));
      assertTrue(bullet.tags().contains("auto"));
      // Lesson is 57 chars truncated
      assertTrue(bullet.title().endsWith("..."),
          "Title should be truncated to 57 chars + '...'");

      // Step 4: Verify the bullet is also discoverable via MemoryManager.list()
      var allBullets = bulletManager.list();
      var bulletInList = allBullets.stream()
          .filter(e -> e.bulletId().equals(bullet.bulletId()))
          .findFirst();
      assertTrue(bulletInList.isPresent(),
          "Bullet should appear in MemoryManager.list() results");

      // Cleanup
      MemoryManager reflectionManager = new MemoryManager(home.resolve("reflections"));
      reflectionManager.delete(feedback.id());
      bulletManager.delete(bullet.id());
    } finally {
      System.clearProperty("codeauto.home");
    }
  }
}
