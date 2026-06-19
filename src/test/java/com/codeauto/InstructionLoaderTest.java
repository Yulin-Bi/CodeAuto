package com.codeauto;

import com.codeauto.instructions.InstructionLoader;
import com.codeauto.memory.MemoryEntry;
import com.codeauto.memory.MemoryManager;
import com.codeauto.memory.MemoryType;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionLoaderTest {
  @Test
  void systemPromptIncludesInstructionsInSpecificityOrder() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-user-home");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-instruction-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());
      Files.createDirectories(userHome.resolve(".claude"));
      Files.writeString(userHome.resolve(".claude").resolve("CLAUDE.md"), "user instruction\n");
      Files.writeString(codeautoHome.resolve("CLAUDE.md"), "app instruction\n");
      Files.writeString(project.resolve("CLAUDE.md"), "project instruction\n");
      Files.writeString(project.resolve("CLAUDE.local.md"), "local instruction\n");

      String prompt = InstructionLoader.systemPrompt(project, "read-only");

      assertTrue(prompt.startsWith("You are CodeAuto. Permissions: read-only"));
      assertInOrder(prompt,
          "user instruction",
          "app instruction",
          "project instruction",
          "local instruction");
      assertTrue(prompt.contains("<system-reminder>"));
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }

  @Test
  void systemPromptStaysCompactWhenNoInstructionFilesExist() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-empty-user-home");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-empty-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-empty-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());

      String prompt = InstructionLoader.systemPrompt(project, "ok");
      assertTrue(prompt.startsWith("You are CodeAuto. Permissions: ok"));
      assertTrue(prompt.contains("Todo behavior:"));
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }

  @Test
  void systemPromptIncludesUserProfile() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-memory-user-home");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-memory-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-memory-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());
      // USER type → goes to user-profile.md (profileStore=true)
      new MemoryManager().save(MemoryType.USER, "Code style", project,
          List.of("style"), "Always use tabs for indentation.");

      String prompt = InstructionLoader.systemPrompt(project, "ok");

      assertTrue(prompt.contains("User Profile"));
      assertTrue(prompt.contains("Code style"));
      assertTrue(prompt.contains("Always use tabs for indentation"));
      // Old "Relevant persistent memories" section is gone — everything is in User Profile
      assertFalse(prompt.contains("Relevant persistent memories"));
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }

  @Test
  void systemPromptOmitsUserProfileWhenEmpty() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-stale-user-home");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-stale-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-stale-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());
      // No user profile saved — prompt should not contain User Profile or Relevant memories sections

      String prompt = InstructionLoader.systemPrompt(project, "ok");

      assertFalse(prompt.contains("User Profile"));
      assertFalse(prompt.contains("Relevant persistent memories"));
      // Base prompt should still be intact
      assertTrue(prompt.startsWith("You are CodeAuto. Permissions: ok"));
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }

  @Test
  void pastExperienceSectionPointsToProjectDirs() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-bullet-quota-user");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-bullet-quota-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-bullet-quota-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());
      MemoryManager memoryManager = new MemoryManager();

      // Save a user preference to trigger the <system-reminder> block
      memoryManager.save(MemoryType.USER, "Test preference", project,
          List.of("test"), "Test content.");

      String prompt = InstructionLoader.systemPrompt(project, "ok");

      // User profile section should exist
      assertTrue(prompt.contains("User Profile"));
      // Past experience section should point to bullets/ and reflections/ dirs
      assertTrue(prompt.contains("Past experience"));
      assertTrue(prompt.contains(project.resolve(".codeauto/bullets").normalize().toString()));
      assertTrue(prompt.contains(project.resolve(".codeauto/reflections").normalize().toString()));
      assertTrue(prompt.contains(project.resolve(".codeauto/reflection-summaries").normalize().toString()));
      assertTrue(prompt.contains("use read_file with that exact relative path"));
      assertTrue(prompt.contains("[bullet:<id>]"));
      // Old sections should NOT appear
      assertFalse(prompt.contains("ACE Playbook"));
      assertFalse(prompt.contains("Relevant persistent memories"));

      // Cleanup
      for (var entry : memoryManager.list()) {
        memoryManager.delete(entry.id());
      }
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }

  @Test
  void systemPromptPlacesTodoSummaryAfterPastExperience() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-order-user-home");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-order-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-order-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());
      new com.codeauto.todo.TodoStore(project).add("Fix cache behavior", "正在修复缓存行为");

      String prompt = InstructionLoader.systemPrompt(project, "ok");
      assertInOrder(prompt, "# Past experience", "# Todo summary");
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }

  @Test
  void systemPromptLimitsBulletIndexVolume() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-bullet-limit-user-home");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-bullet-limit-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-bullet-limit-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());
      MemoryManager manager = new MemoryManager();
      manager.save(MemoryType.USER, "Profile", project, List.of("style"), "Keep replies concise.");

      MemoryManager bulletManager = new MemoryManager(project.resolve(".codeauto/bullets"));
      Instant now = Instant.now();
      for (int i = 0; i < 20; i++) {
        MemoryEntry entry = new MemoryEntry(
            "bullet-" + i,
            MemoryType.PROJECT,
            "Bullet " + i,
            project.toString(),
            List.of("reflection", "auto", "tag" + i),
            now,
            now.plusSeconds(i),
            "Lesson " + i,
            project.resolve(".codeauto/bullets/bullet-" + i + ".md"),
            "tip-" + i,
            20 - i,
            i / 3,
            "common_mistakes");
        bulletManager.overwrite(entry);
      }

      String prompt = InstructionLoader.systemPrompt(project, "ok");
      long bulletLines = prompt.lines().filter(line -> line.startsWith("- [bullet:")).count();
      assertTrue(bulletLines <= 10);
      assertTrue(prompt.contains("(up "));
      assertTrue(prompt.contains(", support "));
      assertTrue(prompt.contains("tags="));
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }

  @Test
  void systemPromptMixesHotRelevantAndExplorationBullets() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-bullet-mix-user-home");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-bullet-mix-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-bullet-mix-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());
      new com.codeauto.todo.TodoStore(project).add("Fix cache invalidation", "正在修复缓存失效");

      MemoryManager bulletManager = new MemoryManager(project.resolve(".codeauto/bullets"));
      Instant now = Instant.now();
      bulletManager.overwrite(new MemoryEntry(
          "hot-bullet",
          MemoryType.PROJECT,
          "Run tests before claiming success",
          project.toString(),
          List.of("reflection", "auto", "testing"),
          now,
          now,
          "Always run mvn test before declaring success.",
          project.resolve(".codeauto/bullets/hot-bullet.md"),
          "tip-hot",
          4,
          0,
          "common_mistakes",
          "hot",
          0,
          0,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          now,
          Instant.EPOCH));
      bulletManager.overwrite(new MemoryEntry(
          "relevant-bullet",
          MemoryType.PROJECT,
          "Check cache invalidation paths",
          project.toString(),
          List.of("reflection", "auto", "cache"),
          now,
          now,
          "Verify cache invalidation paths before restarting the service.",
          project.resolve(".codeauto/bullets/relevant-bullet.md"),
          "tip-cache",
          0,
          0,
          "common_mistakes",
          "warm",
          0,
          0,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH));
      Files.createDirectories(project.resolve(".codeauto/reflection-summaries"));
      Files.writeString(
          project.resolve(".codeauto/reflection-summaries").resolve("tip-cache.md"),
          "# summary\n");
      bulletManager.overwrite(new MemoryEntry(
          "exploration-bullet",
          MemoryType.PROJECT,
          "Review import ordering once before build",
          project.toString(),
          List.of("reflection", "auto", "imports"),
          now,
          now.minusSeconds(60),
          "Review import ordering once before the next build.",
          project.resolve(".codeauto/bullets/exploration-bullet.md"),
          "tip-explore",
          0,
          0,
          "common_mistakes",
          "warm",
          0,
          0,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH));
      bulletManager.overwrite(new MemoryEntry(
          "cold-bullet",
          MemoryType.PROJECT,
          "Outdated lesson",
          project.toString(),
          List.of("reflection", "auto", "legacy"),
          now,
          now,
          "This lesson is outdated and should stay cold.",
          project.resolve(".codeauto/bullets/cold-bullet.md"),
          "tip-cold",
          0,
          3,
          "common_mistakes",
          "cold",
          0,
          0,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          now));

      String prompt = InstructionLoader.systemPrompt(project, "ok");
      assertTrue(prompt.contains("Run tests before claiming success"));
      assertTrue(prompt.contains("Check cache invalidation paths"));
      assertTrue(prompt.contains("Review import ordering once before build"));
      assertTrue(prompt.contains("summaryPath=.codeauto/reflection-summaries/tip-cache.md"));
      assertFalse(prompt.contains("Outdated lesson"));

      var reloaded = bulletManager.list().stream()
          .filter(entry -> entry.bulletId().equals("tip-cache") || entry.bulletId().equals("tip-explore"))
          .toList();
      assertTrue(reloaded.stream().allMatch(entry -> entry.retrieveCount() >= 1));
      assertTrue(reloaded.stream().allMatch(entry -> !entry.lastInjectedAt().equals(Instant.EPOCH)));
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }

  @Test
  void relevantBulletWithSummaryGetsSlightPriority() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-bullet-summary-priority-user-home");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-bullet-summary-priority-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-bullet-summary-priority-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());
      new com.codeauto.todo.TodoStore(project).add("Fix cache invalidation", "修复 cache invalidation 问题");

      MemoryManager bulletManager = new MemoryManager(project.resolve(".codeauto/bullets"));
      Instant now = Instant.now();
      bulletManager.overwrite(new MemoryEntry(
          "summary-bullet",
          MemoryType.PROJECT,
          "Check cache invalidation paths",
          project.toString(),
          List.of("reflection", "auto", "cache"),
          now,
          now.minusSeconds(30),
          "Verify cache invalidation paths before restarting the service.",
          project.resolve(".codeauto/bullets/summary-bullet.md"),
          "tip-cache-summary",
          0,
          0,
          "common_mistakes",
          "warm",
          0,
          0,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH));
      bulletManager.overwrite(new MemoryEntry(
          "plain-bullet",
          MemoryType.PROJECT,
          "Review cache invalidation logic",
          project.toString(),
          List.of("reflection", "auto", "cache"),
          now,
          now,
          "Review cache invalidation logic before restarting the service.",
          project.resolve(".codeauto/bullets/plain-bullet.md"),
          "tip-cache-plain",
          0,
          0,
          "common_mistakes",
          "warm",
          0,
          0,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH));
      Files.createDirectories(project.resolve(".codeauto/reflection-summaries"));
      Files.writeString(
          project.resolve(".codeauto/reflection-summaries").resolve("tip-cache-summary.md"),
          "# summary\n");

      String prompt = InstructionLoader.systemPrompt(project, "ok");
      int summaryIndex = prompt.indexOf("[bullet:tip-cache-summary]");
      int plainIndex = prompt.indexOf("[bullet:tip-cache-plain]");
      assertTrue(summaryIndex >= 0);
      assertTrue(plainIndex >= 0);
      assertTrue(summaryIndex < plainIndex, "summary-backed relevant bullet should appear first");
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }


  private static void assertInOrder(String haystack, String... needles) {
    int cursor = -1;
    for (String needle : needles) {
      int next = haystack.indexOf(needle, cursor + 1);
      assertTrue(next > cursor, "Expected " + needle + " after index " + cursor);
      cursor = next;
    }
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
