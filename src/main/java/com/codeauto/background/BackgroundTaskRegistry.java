package com.codeauto.background;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BackgroundTaskRegistry {
  private static final BackgroundTaskRegistry INSTANCE = new BackgroundTaskRegistry();
  private final Map<String, Entry> tasks = new ConcurrentHashMap<>();

  public static BackgroundTaskRegistry get() {
    return INSTANCE;
  }

  public BackgroundTask start(String command, Process process) {
    String id = UUID.randomUUID().toString().substring(0, 8);
    Entry entry = new Entry(command, process, System.currentTimeMillis());
    tasks.put(id, entry);
    Thread reader = new Thread(() -> readOutput(process.getInputStream(), entry), "codeauto-bg-" + id);
    reader.setDaemon(true);
    reader.start();
    return snapshot(id, entry);
  }

  public List<BackgroundTask> list() {
    List<BackgroundTask> snapshots = new ArrayList<>();
    tasks.forEach((id, entry) -> snapshots.add(snapshot(id, entry)));
    snapshots.sort(java.util.Comparator.comparing(BackgroundTask::startedAt));
    return snapshots;
  }

  public BackgroundTask get(String id) {
    Entry entry = tasks.get(id);
    return entry == null ? null : snapshot(id, entry);
  }

  public boolean kill(String id) {
    Entry entry = tasks.get(id);
    if (entry == null) return false;
    entry.process.destroyForcibly();
    return true;
  }

  private static void readOutput(InputStream stream, Entry entry) {
    try {
      byte[] buffer = new byte[1024];
      int read;
      while ((read = stream.read(buffer)) >= 0) {
        if (read > 0) {
          entry.output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
          if (entry.output.length() > 8000) {
            entry.output.delete(0, entry.output.length() - 8000);
          }
        }
      }
    } catch (Exception ignored) {
      // Output capture is best effort.
    }
  }

  private static BackgroundTask snapshot(String id, Entry entry) {
    String status = entry.process.isAlive()
        ? "running"
        : entry.process.exitValue() == 0 ? "completed" : "failed";
    return new BackgroundTask(id, entry.command, entry.process.pid(), entry.startedAt, status, entry.output.toString());
  }

  private static class Entry {
    final String command;
    final Process process;
    final long startedAt;
    final StringBuilder output = new StringBuilder();

    Entry(String command, Process process, long startedAt) {
      this.command = command;
      this.process = process;
      this.startedAt = startedAt;
    }
  }
}
