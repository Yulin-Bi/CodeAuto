package com.codeauto.undo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class UndoStore {

  private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

  private final Path undoDir;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  public UndoStore(Path cwd) {
    this.undoDir = cwd.resolve(".codeauto").resolve("undo");
  }

  public UndoRecord save(String toolCallId, String toolName, Path absoluteFilePath, String beforeContent) throws Exception {
    lock.writeLock().lock();
    try {
      Files.createDirectories(undoDir);
      String id = UUID.randomUUID().toString().substring(0, 8);
      // Only store paths within the workspace (relative to cwd).
      Path cwd = undoDir.getParent().getParent(); // undoDir is <cwd>/.codeauto/undo
      Path normalized = absoluteFilePath.toAbsolutePath().normalize();
      if (!normalized.startsWith(cwd.toAbsolutePath().normalize())) {
        throw new SecurityException("Cannot save undo record for path outside workspace: " + absoluteFilePath);
      }
      String filePath = cwd.relativize(normalized).toString();
      UndoRecord record = new UndoRecord(
          id, toolCallId, toolName, filePath, beforeContent == null ? "" : beforeContent,
          Instant.now(), false);
      Path file = undoDir.resolve(id + ".json");
      Files.writeString(file, MAPPER.writeValueAsString(record));
      return record;
    } finally {
      lock.writeLock().unlock();
    }
  }

  public UndoRecord load(String id) throws Exception {
    lock.readLock().lock();
    try {
      Path file = undoDir.resolve(id + ".json");
      if (!Files.exists(file)) return null;
      return MAPPER.readValue(Files.readString(file), UndoRecord.class);
    } finally {
      lock.readLock().unlock();
    }
  }

  public List<UndoRecord> list(boolean includeUndone) throws Exception {
    lock.readLock().lock();
    try {
      if (!Files.isDirectory(undoDir)) return List.of();
      List<UndoRecord> records = new ArrayList<>();
      try (var paths = Files.list(undoDir)) {
        for (Path file : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
          try {
            UndoRecord record = MAPPER.readValue(Files.readString(file), UndoRecord.class);
            if (includeUndone || !record.undone()) {
              records.add(record);
            }
          } catch (Exception ignored) {
            // Skip malformed records.
          }
        }
      }
      records.sort(Comparator.comparing(UndoRecord::timestamp));
      return records;
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Returns the most recent non-undone record that was NOT created by an undo operation (i.e. skip redo records). */
  public UndoRecord getLatest() throws Exception {
    List<UndoRecord> records = list(false);
    // Walk backwards to find the first non-undo record
    for (int i = records.size() - 1; i >= 0; i--) {
      if (!"undo".equals(records.get(i).toolName())) {
        return records.get(i);
      }
    }
    return null;
  }

  public void markUndone(String id) throws Exception {
    lock.writeLock().lock();
    try {
      UndoRecord record = load(id);
      if (record != null) {
        Path file = undoDir.resolve(id + ".json");
        Files.writeString(file, MAPPER.writeValueAsString(record.markUndone()));
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void delete(String id) throws Exception {
    lock.writeLock().lock();
    try {
      Files.deleteIfExists(undoDir.resolve(id + ".json"));
    } finally {
      lock.writeLock().unlock();
    }
  }

  public int count() throws Exception {
    return list(false).size();
  }

  /** Resolve a stored filePath back to an absolute Path within the workspace. */
  public Path resolveFilePath(String storedPath) {
    Path cwd = undoDir.getParent().getParent().normalize().toAbsolutePath();
    // Reject paths that look absolute or are obviously trying to escape.
    if (storedPath.contains(":") || storedPath.startsWith("/") || storedPath.startsWith("\\")) {
      throw new SecurityException("Undo path must be relative to workspace, got: " + storedPath);
    }
    Path resolved = cwd.resolve(storedPath).normalize().toAbsolutePath();
    // Validate the resolved path stays within the workspace.
    if (!resolved.startsWith(cwd)) {
      throw new SecurityException("Undo path escapes workspace: " + storedPath + " -> " + resolved);
    }
    return resolved;
  }
}
