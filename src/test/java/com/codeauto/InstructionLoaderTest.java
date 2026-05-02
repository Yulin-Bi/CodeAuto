package com.codeauto;

import com.codeauto.instructions.InstructionLoader;
import com.codeauto.memory.MemoryManager;
import com.codeauto.memory.MemoryType;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

      assertEquals("You are CodeAuto. Permissions: ok", InstructionLoader.systemPrompt(project, "ok"));
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }

  @Test
  void systemPromptIncludesRelevantMemories() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-memory-user-home");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-memory-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-memory-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());
      new MemoryManager().save(MemoryType.PROJECT, "Project preference", project,
          List.of("testing"), "Always run mvn test after permission changes.");

      String prompt = InstructionLoader.systemPrompt(project, "ok");

      assertTrue(prompt.contains("Relevant persistent memories"));
      assertTrue(prompt.contains("Project preference"));
      assertTrue(prompt.contains("Always run mvn test"));
    } finally {
      restoreProperty("codeauto.home", previousHome);
      restoreProperty("user.home", previousUserHome);
    }
  }

  @Test
  void systemPromptMarksOldMemoriesAsStale() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    String previousUserHome = System.getProperty("user.home");
    java.nio.file.Path userHome = Files.createTempDirectory("codeauto-stale-user-home");
    java.nio.file.Path codeautoHome = Files.createTempDirectory("codeauto-stale-home");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-stale-project");
    try {
      System.setProperty("user.home", userHome.toString());
      System.setProperty("codeauto.home", codeautoHome.toString());
      java.nio.file.Path memoryRoot = codeautoHome.resolve("memory");
      Files.createDirectories(memoryRoot);
      Files.writeString(memoryRoot.resolve("old.md"), """
          ---
          id: old
          type: project
          title: Old project note
          project: %s
          tags: stale
          createdAt: 2020-01-01T00:00:00Z
          updatedAt: 2020-01-01T00:00:00Z
          ---

          This old note should be verified.
          """.formatted(project.toAbsolutePath().normalize()));

      String prompt = InstructionLoader.systemPrompt(project, "ok");

      assertTrue(prompt.contains("Old project note [project] [stale]"));
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
