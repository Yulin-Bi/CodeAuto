package com.codeauto;

import com.codeauto.instructions.InstructionLoader;
import com.codeauto.memory.MemoryManager;
import com.codeauto.memory.MemoryType;
import java.nio.file.Files;
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
