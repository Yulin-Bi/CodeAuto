package com.codeauto;

import com.codeauto.curator.Curator;
import com.codeauto.curator.Curator.BulletDelta;
import com.codeauto.memory.MemoryManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuratorTest {

  @Test
  void addBulletCreatesEntryWithBulletFields() throws Exception {
    Path root = Files.createTempDirectory("codeauto-curator-add");
    Path project = Files.createTempDirectory("codeauto-curator-project");
    MemoryManager manager = new MemoryManager(root);
    Curator curator = new Curator(manager);

    List<BulletDelta> deltas = List.of(
        new BulletDelta.Add("tip-001", "Always read before edit",
            "Use read_file before calling edit on any file.", "tool_usage",
            List.of("files", "safety")));

    curator.applyDeltas(project, deltas);
    var playbook = curator.getPlaybook(project);
    assertEquals(1, playbook.size());

    var entry = manager.list().getFirst();
    assertTrue(entry.isBullet());
    assertEquals("tip-001", entry.bulletId());
    assertEquals("tool_usage", entry.section());
    assertTrue(entry.tags().contains("files"));

    manager.delete(entry.id());
  }

  @Test
  void removeBulletDeletesEntry() throws Exception {
    Path root = Files.createTempDirectory("codeauto-curator-remove");
    Path project = Files.createTempDirectory("codeauto-curator-project2");
    MemoryManager manager = new MemoryManager(root);
    Curator curator = new Curator(manager);

    curator.applyDeltas(project, List.of(
        new BulletDelta.Add("tip-002", "Use grep",
            "Use grep for broad text searches.", "tool_usage", List.of())));

    assertEquals(1, curator.getPlaybook(project).size());

    curator.applyDeltas(project, List.of(new BulletDelta.Remove("tip-002")));
    assertTrue(curator.getPlaybook(project).isEmpty());
  }

  @Test
  void tagBulletAddsAndRemovesTags() throws Exception {
    Path root = Files.createTempDirectory("codeauto-curator-tag");
    Path project = Files.createTempDirectory("codeauto-curator-project3");
    MemoryManager manager = new MemoryManager(root);
    Curator curator = new Curator(manager);

    curator.applyDeltas(project, List.of(
        new BulletDelta.Add("tip-003", "Java conventions",
            "Use camelCase for method names.", "style", List.of("java"))));

    curator.applyDeltas(project, List.of(
        new BulletDelta.Tag("tip-003", List.of("important"), List.of("java"))));

    var playbook = curator.getPlaybook(project);
    assertEquals(1, playbook.size());
    var entry = playbook.getFirst();
    assertTrue(entry.tags().contains("important"));
    assertFalse(entry.tags().contains("java"));
  }

  @Test
  void incrementCountersModifiesHelpfulAndHarmful() throws Exception {
    Path root = Files.createTempDirectory("codeauto-curator-counter");
    Path project = Files.createTempDirectory("codeauto-curator-project4");
    MemoryManager manager = new MemoryManager(root);
    Curator curator = new Curator(manager);

    curator.applyDeltas(project, List.of(
        new BulletDelta.Add("tip-004", "Check encoding",
            "Always verify file encoding before reading.", "common_mistakes", List.of())));

    curator.applyDeltas(project, List.of(
        new BulletDelta.Update("tip-004", null, 3, 1, null)));

    var playbook = curator.getPlaybook(project);
    assertEquals(1, playbook.size());
    var entry = playbook.getFirst();
    assertEquals("tip-004", entry.bulletId());
    assertEquals(3, entry.helpfulCount());
    assertEquals(1, entry.harmfulCount());
  }

  @Test
  void getPlaybookFiltersByProject() throws Exception {
    Path root = Files.createTempDirectory("codeauto-curator-filter");
    Path projectA = Files.createTempDirectory("codeauto-project-a");
    Path projectB = Files.createTempDirectory("codeauto-project-b");
    MemoryManager manager = new MemoryManager(root);
    Curator curator = new Curator(manager);

    curator.applyDeltas(projectA, List.of(
        new BulletDelta.Add("tip-a", "Project A tip", "Tip for A.", "general", List.of())));
    curator.applyDeltas(projectB, List.of(
        new BulletDelta.Add("tip-b", "Project B tip", "Tip for B.", "general", List.of())));

    assertEquals(1, curator.getPlaybook(projectA).size());
    assertEquals("tip-a", curator.getPlaybook(projectA).getFirst().bulletId());
    assertEquals(1, curator.getPlaybook(projectB).size());
    assertEquals("tip-b", curator.getPlaybook(projectB).getFirst().bulletId());
  }

  @Test
  void updateBulletContentPreservesBulletId() throws Exception {
    Path root = Files.createTempDirectory("codeauto-curator-update");
    Path project = Files.createTempDirectory("codeauto-curator-project5");
    MemoryManager manager = new MemoryManager(root);
    Curator curator = new Curator(manager);

    curator.applyDeltas(project, List.of(
        new BulletDelta.Add("tip-005", "Original title",
            "Original content.", "section1", List.of())));

    curator.applyDeltas(project, List.of(
        new BulletDelta.Update("tip-005", "Updated content.", 0, 0, "section2")));

    var playbook = curator.getPlaybook(project);
    assertEquals(1, playbook.size());
    var entry = playbook.getFirst();
    assertEquals("tip-005", entry.bulletId());
    assertTrue(entry.content().contains("Updated content."));
    assertEquals("section2", entry.section());
  }

  @Test
  void jaccardSimilarityZeroForUnrelated() {
    assertEquals(1.0, Curator.jaccardSimilarity("hello world", "hello world"), 0.01);
    assertEquals(0.0, Curator.jaccardSimilarity("hello", "world"), 0.01);
    assertTrue(Curator.jaccardSimilarity("always use read_file before edit",
        "use read_file before editing any file") >= 0.5);
  }

  @Test
  void similarBulletTriggersMergeInsteadOfDuplicate() throws Exception {
    Path root = Files.createTempDirectory("codeauto-curator-merge");
    Path project = Files.createTempDirectory("codeauto-curator-project6");
    MemoryManager manager = new MemoryManager(root);
    Curator curator = new Curator(manager);

    curator.applyDeltas(project, List.of(
        new BulletDelta.Add("tip-006", "Read before edit",
            "Always use read_file to check file contents before calling edit.",
            "tool_usage", List.of())));

    // Add a very similar bullet — should merge instead of creating duplicate
    curator.applyDeltas(project, List.of(
        new BulletDelta.Add("tip-007", "Read before edit v2",
            "Always use read_file to check file contents before calling edit tool.",
            "tool_usage", List.of())));

    var playbook = curator.getPlaybook(project);
    assertEquals(1, playbook.size());
    assertEquals("tip-006", playbook.getFirst().bulletId());
    assertEquals(1, playbook.getFirst().helpfulCount());
  }
}
