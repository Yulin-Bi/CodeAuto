package com.codeauto.curator;

import com.codeauto.memory.MemoryEntry;
import com.codeauto.memory.MemoryManager;
import com.codeauto.memory.MemoryType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class Curator {

  private final MemoryManager memory;

  public Curator(MemoryManager memory) {
    this.memory = memory;
  }

  public Curator() {
    this(new MemoryManager());
  }

  public sealed interface BulletDelta
      permits BulletDelta.Add, BulletDelta.Update, BulletDelta.Tag, BulletDelta.Remove {

    record Add(
        String bulletId,
        String title,
        String content,
        String section,
        List<String> tags
    ) implements BulletDelta {}

    record Update(
        String bulletId,
        String newContent,
        int helpfulDelta,
        int harmfulDelta,
        String newSection
    ) implements BulletDelta {}

    record Tag(
        String bulletId,
        List<String> addTags,
        List<String> removeTags
    ) implements BulletDelta {}

    record Remove(String bulletId) implements BulletDelta {}
  }

  public void applyDeltas(Path project, List<BulletDelta> deltas) {
    for (BulletDelta delta : deltas) {
      applySingle(project, delta);
    }
  }

  private void applySingle(Path project, BulletDelta delta) {
    switch (delta) {
      case BulletDelta.Add add -> {
        String existingId = findSimilarBullet(project, add.content(), add.title(), add.section());
        if (existingId != null) {
          memory.incrementCounters(existingId, 1, 0);
        } else {
          memory.saveBullet(
              MemoryType.PROJECT,
              add.title(),
              project,
              add.tags() == null ? List.of() : add.tags(),
              add.content(),
              add.bulletId(),
              add.section() == null ? "" : add.section());
        }
      }
      case BulletDelta.Update update -> {
        Optional<MemoryEntry> found = findBulletByBulletId(project, update.bulletId());
        if (found.isEmpty()) return;
        MemoryEntry entry = found.get();
        String newContent = update.newContent() != null ? update.newContent() : entry.content();
        String newSection = update.newSection() != null ? update.newSection() : entry.section();
        memory.delete(entry.id());
        MemoryEntry created = memory.saveBullet(
            entry.type(), entry.title(), project, entry.tags(),
            newContent, entry.bulletId(), newSection);
        if (update.helpfulDelta() != 0 || update.harmfulDelta() != 0) {
          memory.incrementCounters(created.bulletId(), update.helpfulDelta(), update.harmfulDelta());
        }
      }
      case BulletDelta.Tag tag -> {
        Optional<MemoryEntry> found = findBulletByBulletId(project, tag.bulletId());
        if (found.isEmpty()) return;
        MemoryEntry entry = found.get();
        List<String> newTags = new ArrayList<>(entry.tags());
        if (tag.addTags() != null) {
          for (String t : tag.addTags()) {
            if (!newTags.contains(t)) newTags.add(t);
          }
        }
        if (tag.removeTags() != null) {
          newTags.removeAll(tag.removeTags());
        }
        MemoryEntry updated = new MemoryEntry(
            entry.id(), entry.type(), entry.title(), entry.project(),
            newTags, entry.createdAt(), Instant.now(), entry.content(),
            entry.path(), entry.bulletId(),
            entry.helpfulCount(), entry.harmfulCount(), entry.section());
        memory.overwrite(updated);
      }
      case BulletDelta.Remove remove -> {
        findBulletByBulletId(project, remove.bulletId())
            .ifPresent(b -> memory.delete(b.id()));
      }
    }
  }

  public List<MemoryEntry> getPlaybook(Path project) {
    return memory.list().stream()
        .filter(e -> e.isBullet() && projectMatches(e, project))
        .toList();
  }

  private Optional<MemoryEntry> findBulletByBulletId(Path project, String bulletId) {
    return getPlaybook(project).stream()
        .filter(b -> b.bulletId().equals(bulletId))
        .findFirst();
  }

  private String findSimilarBullet(Path project, String newContent, String newTitle, String section) {
    return getPlaybook(project).stream()
        .filter(b -> section == null || section.isBlank() || b.section().equals(section))
        .filter(b -> jaccardSimilarity(newContent, b.content()) >= 0.55)
        .findFirst()
        .map(MemoryEntry::bulletId)
        .orElse(null);
  }

  public static double jaccardSimilarity(String a, String b) {
    if (a == null || b == null || a.isBlank() || b.isBlank()) return 0.0;
    Set<String> wordsA = wordSet(a);
    Set<String> wordsB = wordSet(b);
    if (wordsA.isEmpty() && wordsB.isEmpty()) return 0.0;
    Set<String> union = new HashSet<>(wordsA);
    union.addAll(wordsB);
    Set<String> intersection = new HashSet<>(wordsA);
    intersection.retainAll(wordsB);
    return (double) intersection.size() / union.size();
  }

  private static Set<String> wordSet(String text) {
    Set<String> words = new HashSet<>();
    for (String part : text.toLowerCase().split("[^a-z0-9]+")) {
      if (part.length() >= 2) words.add(part);
    }
    return words;
  }

  private boolean projectMatches(MemoryEntry entry, Path project) {
    if (project == null) return true;
    return entry.project().equals(project.toAbsolutePath().normalize().toString());
  }
}
