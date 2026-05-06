package com.codeauto;

import com.codeauto.memory.MemoryManager;
import com.codeauto.memory.MemoryType;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {
  @Test
  void savesListsAndDeletesMarkdownMemories() throws Exception {
    java.nio.file.Path root = Files.createTempDirectory("codeauto-memory");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-memory-project");
    MemoryManager manager = new MemoryManager(root);

    var entry = manager.save(MemoryType.PROJECT, "Architecture Decision", project,
        List.of("architecture", "java"), "Use JLine for the TUI.");

    assertTrue(Files.exists(entry.path()));
    assertEquals(1, manager.list().size());
    assertTrue(manager.list().getFirst().content().contains("Use JLine"));
    assertTrue(manager.delete(entry.id()));
    assertTrue(manager.list().isEmpty());
  }

  @Test
  void retrievesRelevantProjectAndKeywordMemories() throws Exception {
    java.nio.file.Path root = Files.createTempDirectory("codeauto-memory-relevant");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-project-alpha");
    java.nio.file.Path otherProject = Files.createTempDirectory("codeauto-project-beta");
    MemoryManager manager = new MemoryManager(root);

    manager.save(MemoryType.PROJECT, "CodeAuto context", project,
        List.of("codeauto"), "Permission rules use Bash(pattern).");
    manager.save(MemoryType.PROJECT, "Other project", otherProject,
        List.of("other"), "Unrelated note.");

    var relevant = manager.relevant(project, "permission", 5);

    assertFalse(relevant.isEmpty());
    assertEquals("CodeAuto context", relevant.getFirst().title());
  }

  @Test
  void savesAndParsesBulletFields() throws Exception {
    java.nio.file.Path root = Files.createTempDirectory("codeauto-memory-bullet");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-memory-bullet-project");
    MemoryManager manager = new MemoryManager(root);

    var entry = manager.saveBullet(null, "Use read before edit",
        project, List.of("tooling"), "Always read before editing.",
        "tip-001", "tool_usage");

    assertTrue(entry.isBullet());
    assertEquals("tip-001", entry.bulletId());
    assertEquals("tool_usage", entry.section());
    assertEquals(0, entry.helpfulCount());
    assertEquals(0, entry.harmfulCount());

    // Verify round-trip through parse
    var reloaded = manager.list().stream()
        .filter(e -> e.bulletId().equals("tip-001"))
        .findFirst();
    assertTrue(reloaded.isPresent());
    assertEquals("tip-001", reloaded.get().bulletId());
    assertEquals("tool_usage", reloaded.get().section());
    assertTrue(reloaded.get().tags().contains("tooling"));

    manager.delete(entry.id());
  }

  @Test
  void defaultCountersAreZeroForNonBullet() throws Exception {
    java.nio.file.Path root = Files.createTempDirectory("codeauto-memory-nonbullet");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-memory-nonbullet-project");
    MemoryManager manager = new MemoryManager(root);

    var entry = manager.save(MemoryType.PROJECT, "Regular memory",
        project, List.of(), "Some content.");

    assertFalse(entry.isBullet());
    assertEquals("", entry.bulletId());
    assertEquals(0, entry.helpfulCount());
    assertEquals(0, entry.harmfulCount());
    assertEquals("", entry.section());

    manager.delete(entry.id());
  }
}
