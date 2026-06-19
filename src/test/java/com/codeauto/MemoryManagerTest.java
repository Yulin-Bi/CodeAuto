package com.codeauto;

import com.codeauto.memory.MemoryManager;
import com.codeauto.memory.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    assertEquals("warm", entry.tier());
    assertEquals(0, entry.supportCount());
    assertEquals(0, entry.retrieveCount());

    // Verify round-trip through parse
    var reloaded = manager.list().stream()
        .filter(e -> e.bulletId().equals("tip-001"))
        .findFirst();
    assertTrue(reloaded.isPresent());
    assertEquals("tip-001", reloaded.get().bulletId());
    assertEquals("tool_usage", reloaded.get().section());
    assertTrue(reloaded.get().tags().contains("tooling"));
    assertEquals("warm", reloaded.get().tier());
    assertEquals(0, reloaded.get().supportCount());
    assertEquals(0, reloaded.get().retrieveCount());
    String raw = Files.readString(Path.of(entry.path().toString()));
    assertTrue(raw.contains("tier: warm"));
    assertTrue(raw.contains("supportCount: 0"));
    assertTrue(raw.contains("retrieveCount: 0"));
    assertTrue(raw.contains("helpfulCount: 0"));
    assertTrue(raw.contains("harmfulCount: 0"));

    manager.delete(entry.id());
  }

  @Test
  void recordsBulletUsageAndHelpfulTimestamps() throws Exception {
    java.nio.file.Path root = Files.createTempDirectory("codeauto-memory-usage");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-memory-usage-project");
    MemoryManager manager = new MemoryManager(root);

    var entry = manager.saveBullet(null, "Check build before edit",
        project, List.of("build"), "Run mvn test before editing.",
        "tip-usage", "common_mistakes");

    assertTrue(manager.recordRetrieval("tip-usage"));
    assertTrue(manager.recordInjection("tip-usage"));
    assertTrue(manager.recordSupport("tip-usage", List.of("verified", "build")));
    assertTrue(manager.incrementCounters("tip-usage", 2, 0));

    var reloaded = manager.list().stream()
        .filter(e -> e.bulletId().equals("tip-usage"))
        .findFirst()
        .orElseThrow();
    assertEquals(1, reloaded.retrieveCount());
    assertEquals(1, reloaded.supportCount());
    assertNotEquals(Instant.EPOCH, reloaded.lastRetrievedAt());
    assertNotEquals(Instant.EPOCH, reloaded.lastInjectedAt());
    assertNotEquals(Instant.EPOCH, reloaded.lastSupportedAt());
    assertNotEquals(Instant.EPOCH, reloaded.lastHelpfulAt());
    assertEquals("hot", reloaded.tier());
    assertTrue(reloaded.tags().contains("verified"));
  }

  @Test
  void parsesLegacyBulletWithoutNewFields() throws Exception {
    java.nio.file.Path root = Files.createTempDirectory("codeauto-memory-legacy");
    java.nio.file.Path file = root.resolve("legacy.md");
    Files.createDirectories(root);
    Files.writeString(file, """
        ---
        id: legacy
        type: project
        title: Legacy bullet
        project: D:\\test
        tags: reflection,auto
        createdAt: 2026-01-01T00:00:00Z
        updatedAt: 2026-01-01T00:00:00Z
        bulletId: tip-legacy
        helpfulCount: 3
        harmfulCount: 0
        section: common_mistakes
        ---

        Legacy lesson
        """);
    MemoryManager manager = new MemoryManager(root);

    var reloaded = manager.list().getFirst();
    assertEquals("tip-legacy", reloaded.bulletId());
    assertEquals("hot", reloaded.tier());
    assertEquals(0, reloaded.supportCount());
    assertEquals(0, reloaded.retrieveCount());
    assertEquals(Instant.EPOCH, reloaded.lastRetrievedAt());
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
