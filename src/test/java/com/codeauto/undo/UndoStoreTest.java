package com.codeauto.undo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoStoreTest {

  private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void saveCreatesRecordFile() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-save-test");
    try {
      UndoStore store = new UndoStore(cwd);
      UndoRecord record = store.save("call-1", "Wrote", cwd.resolve("hello.txt"), "old content");

      assertNotNull(record.id());
      assertTrue(record.id().length() == 8);
      // Verify file exists on disk
      Path recordFile = cwd.resolve(".codeauto/undo").resolve(record.id() + ".json");
      assertTrue(Files.exists(recordFile));

      // Verify content is valid JSON
      UndoRecord loaded = MAPPER.readValue(Files.readString(recordFile), UndoRecord.class);
      assertEquals("call-1", loaded.toolCallId());
      assertEquals("Wrote", loaded.toolName());
      assertEquals("hello.txt", loaded.filePath());
      assertEquals("old content", loaded.beforeContent());
      assertFalse(loaded.undone());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void loadReturnsSavedRecord() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-load-test");
    try {
      UndoStore store = new UndoStore(cwd);
      UndoRecord saved = store.save("call-1", "Edited", cwd.resolve("test.txt"), "before");
      UndoRecord loaded = store.load(saved.id());

      assertEquals(saved.id(), loaded.id());
      assertEquals(saved.toolName(), loaded.toolName());
      assertEquals(saved.filePath(), loaded.filePath());
      assertEquals(saved.beforeContent(), loaded.beforeContent());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void listReturnsRecordsSortedByTimestamp() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-list-test");
    try {
      UndoStore store = new UndoStore(cwd);
      store.save("c1", "Wrote", cwd.resolve("a.txt"), "a");
      Thread.sleep(10); // Ensure distinct timestamps
      store.save("c2", "Edited", cwd.resolve("b.txt"), "b");
      Thread.sleep(10);
      store.save("c3", "Modified", cwd.resolve("c.txt"), "c");

      List<UndoRecord> records = store.list(false);
      assertEquals(3, records.size());
      // Sorted by timestamp ascending
      assertEquals("a.txt", records.get(0).filePath());
      assertEquals("b.txt", records.get(1).filePath());
      assertEquals("c.txt", records.get(2).filePath());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void listExcludesUndoneRecords() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-list-undone-test");
    try {
      UndoStore store = new UndoStore(cwd);
      UndoRecord r1 = store.save("c1", "Wrote", cwd.resolve("a.txt"), "a");
      store.save("c2", "Edited", cwd.resolve("b.txt"), "b");
      store.markUndone(r1.id());

      List<UndoRecord> active = store.list(false);
      assertEquals(1, active.size());
      assertEquals("b.txt", active.get(0).filePath());

      List<UndoRecord> all = store.list(true);
      assertEquals(2, all.size());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void getLatestReturnsMostRecent() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-latest-test");
    try {
      UndoStore store = new UndoStore(cwd);
      assertNull(store.getLatest());

      store.save("c1", "Wrote", cwd.resolve("a.txt"), "a");
      Thread.sleep(10);
      UndoRecord latest = store.save("c2", "Edited", cwd.resolve("b.txt"), "b");

      assertEquals(latest.id(), store.getLatest().id());

      // Mark latest as undone; next most recent should become latest
      store.markUndone(latest.id());
      UndoRecord newLatest = store.getLatest();
      assertNotNull(newLatest);
      assertEquals("a.txt", newLatest.filePath());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void markUndonePersistsToDisk() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-markundone-test");
    try {
      UndoStore store = new UndoStore(cwd);
      UndoRecord record = store.save("c1", "Wrote", cwd.resolve("f.txt"), "before");
      store.markUndone(record.id());

      // Load from a new store instance (simulating different turn)
      UndoStore store2 = new UndoStore(cwd);
      UndoRecord reloaded = store2.load(record.id());
      assertTrue(reloaded.undone());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void concurrentSavesDoNotCorrupt() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-concurrent-test");
    try {
      UndoStore store = new UndoStore(cwd);
      int threads = 4;
      int perThread = 25;
      CountDownLatch latch = new CountDownLatch(threads);
      AtomicInteger errors = new AtomicInteger();

      for (int t = 0; t < threads; t++) {
        final int tid = t;
        new Thread(() -> {
          try {
            for (int i = 0; i < perThread; i++) {
              store.save("call-" + tid, "Wrote", cwd.resolve("file-" + tid + "-" + i + ".txt"), "content");
            }
          } catch (Exception e) {
            errors.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }).start();
      }
      latch.await();

      assertEquals(0, errors.get());
      assertEquals(threads * perThread, store.count());
      assertEquals(threads * perThread, store.list(true).size());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void saveEmptyBeforeContentForNewFile() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-empty-test");
    try {
      UndoStore store = new UndoStore(cwd);
      UndoRecord record = store.save("c1", "Wrote", cwd.resolve("new.txt"), "");

      assertTrue(record.beforeContent().isEmpty());
      assertEquals("new.txt", record.filePath());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void storeIsolatedPerCwd() throws Exception {
    Path cwd1 = Files.createTempDirectory("codeauto-undo-isolated-1");
    Path cwd2 = Files.createTempDirectory("codeauto-undo-isolated-2");
    try {
      UndoStore store1 = new UndoStore(cwd1);
      UndoStore store2 = new UndoStore(cwd2);

      store1.save("c1", "Wrote", cwd1.resolve("a.txt"), "a");
      store2.save("c2", "Edited", cwd2.resolve("b.txt"), "b");

      assertEquals(1, store1.count());
      assertEquals(1, store2.count());
      assertEquals("a.txt", store1.getLatest().filePath());
      assertEquals("b.txt", store2.getLatest().filePath());
    } finally {
      deleteRecursively(cwd1);
      deleteRecursively(cwd2);
    }
  }

  @Test
  void loadReturnsNullForNonexistentId() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-null-test");
    try {
      UndoStore store = new UndoStore(cwd);
      assertNull(store.load("nonexistent"));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void deleteRemovesRecordFile() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-delete-test");
    try {
      UndoStore store = new UndoStore(cwd);
      UndoRecord record = store.save("c1", "Wrote", cwd.resolve("f.txt"), "content");
      Path recordFile = cwd.resolve(".codeauto/undo").resolve(record.id() + ".json");
      assertTrue(Files.exists(recordFile));

      store.delete(record.id());
      assertFalse(Files.exists(recordFile));
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void resolveFilePathRelativeToWorkspace() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-resolve-relative");
    try {
      UndoStore store = new UndoStore(cwd);
      store.save("c1", "Wrote", cwd.resolve("subdir/file.txt"), "content");

      UndoRecord record = store.getLatest();
      // Path separators are platform-dependent (backslash on Windows)
      assertEquals("subdir" + java.io.File.separator + "file.txt", record.filePath());

      Path resolved = store.resolveFilePath(record.filePath());
      assertEquals(cwd.resolve("subdir/file.txt").normalize(), resolved.normalize());
    } finally {
      deleteRecursively(cwd);
    }
  }

  @Test
  void countReturnsActiveRecordsOnly() throws Exception {
    Path cwd = Files.createTempDirectory("codeauto-undo-count-test");
    try {
      UndoStore store = new UndoStore(cwd);
      assertEquals(0, store.count());

      UndoRecord r1 = store.save("c1", "Wrote", cwd.resolve("a.txt"), "a");
      assertEquals(1, store.count());

      store.save("c2", "Edited", cwd.resolve("b.txt"), "b");
      assertEquals(2, store.count());

      store.markUndone(r1.id());
      assertEquals(1, store.count());
    } finally {
      deleteRecursively(cwd);
    }
  }

  private static void deleteRecursively(Path path) {
    try {
      if (Files.isDirectory(path)) {
        try (var paths = Files.walk(path)) {
          paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
          });
        }
      } else {
        Files.deleteIfExists(path);
      }
    } catch (Exception ignored) {}
  }
}
