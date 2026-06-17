package com.codeauto.background;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class BackgroundTaskRegistry {
  private static final BackgroundTaskRegistry INSTANCE = new BackgroundTaskRegistry();
  private static final long UNKNOWN_PID = -1L;

  private final Map<String, Entry> tasks = new ConcurrentHashMap<>();
  private final BackgroundTaskStore store = new BackgroundTaskStore();
  private final Object lock = new Object();
  private Path loadedHome;

  @FunctionalInterface
  public interface RestartValidator {
    void validate(List<String> commandParts, Path cwd) throws Exception;
  }

  public static BackgroundTaskRegistry get() {
    return INSTANCE;
  }

  public BackgroundTask start(
      String appId,
      String command,
      List<String> commandParts,
      Path cwd,
      Process process,
      String healthUrl,
      int healthPort,
      int startupTimeoutSeconds) {
    ensureLoaded();
    String normalizedAppId = normalizeAppId(appId);
    String id = existingIdForApp(normalizedAppId);
    if (id == null) {
      id = UUID.randomUUID().toString().substring(0, 8);
    }
    Entry entry = Entry.attached(
        normalizedAppId,
        command,
        List.copyOf(commandParts),
        cwd,
        process,
        System.currentTimeMillis(),
        normalizeHealthUrl(healthUrl),
        normalizeHealthPort(healthPort),
        normalizeStartupTimeout(startupTimeoutSeconds));
    tasks.put(id, entry);
    startReader(id, process.getInputStream(), entry);
    persistManagedApps();
    return snapshot(id, entry);
  }

  public List<BackgroundTask> list() {
    ensureLoaded();
    List<BackgroundTask> snapshots = new ArrayList<>();
    tasks.forEach((id, entry) -> snapshots.add(snapshotAndRefresh(id, entry)));
    snapshots.sort(java.util.Comparator.comparing(BackgroundTask::startedAt));
    return snapshots;
  }

  public BackgroundTask get(String id) {
    ensureLoaded();
    Entry entry = tasks.get(id);
    return entry == null ? null : snapshotAndRefresh(id, entry);
  }

  public BackgroundTask getByAppId(String appId) {
    ensureLoaded();
    if (appId == null || appId.isBlank()) return null;
    for (var item : tasks.entrySet()) {
      if (appId.equals(item.getValue().appId)) {
        return snapshotAndRefresh(item.getKey(), item.getValue());
      }
    }
    return null;
  }

  public boolean hasRunningAppId(String appId) {
    ensureLoaded();
    if (appId == null || appId.isBlank()) return false;
    for (Entry entry : tasks.values()) {
      if (appId.equals(entry.appId) && entry.isAlive()) {
        return true;
      }
    }
    return false;
  }

  public boolean kill(String id) {
    ensureLoaded();
    Entry entry = tasks.get(id);
    if (entry == null) return false;
    destroy(entry);
    entry.status = "stopped";
    persistManagedApps();
    return true;
  }

  public boolean killByAppId(String appId) {
    ensureLoaded();
    if (appId == null || appId.isBlank()) return false;
    for (var item : tasks.entrySet()) {
      if (appId.equals(item.getValue().appId)) {
        destroy(item.getValue());
        item.getValue().status = "stopped";
        persistManagedApps();
        return true;
      }
    }
    return false;
  }

  public BackgroundTask restart(String id, RestartValidator validator) throws Exception {
    ensureLoaded();
    Entry existing = tasks.get(id);
    if (existing == null) return null;
    return restartInternal(id, existing, validator);
  }

  public BackgroundTask restartByAppId(String appId, RestartValidator validator) throws Exception {
    ensureLoaded();
    if (appId == null || appId.isBlank()) return null;
    for (var item : tasks.entrySet()) {
      if (appId.equals(item.getValue().appId)) {
        return restartInternal(item.getKey(), item.getValue(), validator);
      }
    }
    return null;
  }

  private BackgroundTask restartInternal(String id, Entry existing, RestartValidator validator) throws Exception {
    if (validator != null) {
      validator.validate(existing.commandParts, existing.cwd);
    }
    destroy(existing);
    Process process = new ProcessBuilder(existing.commandParts)
        .directory(existing.cwd.toFile())
        .redirectErrorStream(true)
        .start();
    Entry replacement = Entry.attached(existing.appId, existing.command, existing.commandParts, existing.cwd,
        process, System.currentTimeMillis(), existing.healthUrl, existing.healthPort, existing.startupTimeoutSeconds);
    tasks.put(id, replacement);
    startReader(id, process.getInputStream(), replacement);
    persistManagedApps();
    return snapshot(id, replacement);
  }

  public BackgroundTask awaitReady(String id) throws Exception {
    ensureLoaded();
    Entry entry = tasks.get(id);
    if (entry == null) return null;
    waitForHealth(entry);
    persistManagedApps();
    return snapshot(id, entry);
  }

  private static void startReader(String id, InputStream stream, Entry entry) {
    Thread reader = new Thread(() -> readOutput(stream, entry), "codeauto-bg-" + id);
    reader.setDaemon(true);
    reader.start();
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

  private BackgroundTask snapshotAndRefresh(String id, Entry entry) {
    boolean changed = refreshStatus(entry);
    if (changed) {
      persistManagedApps();
    }
    return snapshot(id, entry);
  }

  private static BackgroundTask snapshot(String id, Entry entry) {
    return new BackgroundTask(
        id,
        entry.appId,
        entry.command,
        entry.cwd.toString(),
        entry.pid,
        entry.startedAt,
        currentStatus(entry),
        entry.healthUrl,
        entry.healthPort,
        entry.startupTimeoutSeconds,
        currentHealthStatus(entry),
        entry.output.toString());
  }

  private boolean refreshStatus(Entry entry) {
    String status = currentStatus(entry);
    if (status.equals(entry.status)) {
      return false;
    }
    entry.status = status;
    return true;
  }

  private static String currentStatus(Entry entry) {
    if ("stopped".equals(entry.status)) {
      return "stopped";
    }
    if ("failed".equals(entry.healthStatus) || "timeout".equals(entry.healthStatus)) {
      return "failed";
    }
    if (entry.isAlive()) {
      return "running";
    }
    if (entry.process != null) {
      try {
        return entry.process.exitValue() == 0 ? "completed" : "failed";
      } catch (IllegalThreadStateException ignored) {
        return "running";
      }
    }
    return "running".equals(entry.status) ? "exited" : entry.status;
  }

  private static String currentHealthStatus(Entry entry) {
    if (!entry.hasHealthCheck()) {
      return "";
    }
    if (entry.isAlive() && ManagedAppHealthChecker.isHealthy(entry.healthUrl, entry.healthPort)) {
      return "ready";
    }
    if ("ready".equals(entry.healthStatus)) {
      return "unreachable";
    }
    return entry.healthStatus == null ? "" : entry.healthStatus;
  }

  private void ensureLoaded() {
    synchronized (lock) {
      Path currentHome = BackgroundTaskStore.storeHome();
      if (currentHome.equals(loadedHome)) {
        return;
      }
      tasks.clear();
      for (var persisted : store.readManagedApps()) {
        Path cwd = Path.of(persisted.workdir()).toAbsolutePath().normalize();
        Entry entry = Entry.detached(
            normalizeAppId(persisted.appId()),
            persisted.command(),
            persisted.commandParts(),
            cwd,
            persisted.pid(),
            persisted.startedAt(),
            persisted.status(),
            persisted.healthUrl(),
            persisted.healthPort(),
            persisted.startupTimeoutSeconds(),
            persisted.healthStatus());
        tasks.put(persisted.taskId(), entry);
      }
      loadedHome = currentHome;
    }
  }

  private void persistManagedApps() {
    synchronized (lock) {
      if (loadedHome == null) {
        loadedHome = BackgroundTaskStore.storeHome();
      }
      List<BackgroundTaskStore.StoredManagedApp> persisted = new ArrayList<>();
      for (var item : tasks.entrySet()) {
        Entry entry = item.getValue();
        if (entry.appId == null || entry.appId.isBlank()) {
          continue;
        }
        refreshStatus(entry);
        persisted.add(new BackgroundTaskStore.StoredManagedApp(
            item.getKey(),
            entry.appId,
            entry.command,
            entry.commandParts,
            entry.cwd.toString(),
            entry.pid,
            entry.startedAt,
            entry.status,
            entry.healthUrl,
            entry.healthPort,
            entry.startupTimeoutSeconds,
            currentHealthStatus(entry)));
      }
      store.writeManagedApps(persisted);
    }
  }

  private static String normalizeAppId(String appId) {
    return appId == null || appId.isBlank() ? null : appId.trim();
  }

  private static String normalizeHealthUrl(String healthUrl) {
    return healthUrl == null || healthUrl.isBlank() ? null : healthUrl.trim();
  }

  private static int normalizeHealthPort(int healthPort) {
    return healthPort > 0 ? healthPort : 0;
  }

  private static int normalizeStartupTimeout(int startupTimeoutSeconds) {
    return startupTimeoutSeconds > 0 ? startupTimeoutSeconds : 20;
  }

  private String existingIdForApp(String appId) {
    if (appId == null || appId.isBlank()) {
      return null;
    }
    for (var item : tasks.entrySet()) {
      if (appId.equals(item.getValue().appId)) {
        return item.getKey();
      }
    }
    return null;
  }

  private static void destroy(Entry entry) {
    if (entry.process != null) {
      entry.process.descendants().forEach(ProcessHandle::destroyForcibly);
      entry.process.destroyForcibly();
      try {
        entry.process.waitFor(2, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return;
    }
    if (entry.pid == UNKNOWN_PID) {
      return;
    }
    ProcessHandle.of(entry.pid).ifPresent(handle -> {
      handle.descendants().forEach(ProcessHandle::destroyForcibly);
      handle.destroyForcibly();
    });
  }

  private static void waitForHealth(Entry entry) throws Exception {
    if (!entry.hasHealthCheck()) {
      return;
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, entry.startupTimeoutSeconds));
    entry.healthStatus = "starting";
    while (System.nanoTime() < deadline) {
      if (!entry.isAlive()) {
        entry.status = "failed";
        entry.healthStatus = "failed";
        throw new IllegalStateException("Managed app exited before becoming ready");
      }
      if (ManagedAppHealthChecker.isHealthy(entry.healthUrl, entry.healthPort)) {
        entry.healthStatus = "ready";
        return;
      }
      Thread.sleep(250);
    }
    entry.healthStatus = "timeout";
    destroy(entry);
    entry.status = "failed";
    throw new IllegalStateException("Managed app did not become ready within " + entry.startupTimeoutSeconds + "s");
  }

  private static class Entry {
    final String appId;
    final String command;
    final List<String> commandParts;
    final Path cwd;
    volatile Process process;
    volatile long pid;
    volatile long startedAt;
    volatile String status;
    final String healthUrl;
    final int healthPort;
    final int startupTimeoutSeconds;
    volatile String healthStatus;
    final StringBuilder output = new StringBuilder();

    private Entry(String appId, String command, List<String> commandParts, Path cwd,
        Process process, long pid, long startedAt, String status,
        String healthUrl, int healthPort, int startupTimeoutSeconds, String healthStatus) {
      this.appId = appId == null || appId.isBlank() ? null : appId;
      this.command = command;
      this.commandParts = commandParts;
      this.cwd = cwd.toAbsolutePath().normalize();
      this.process = process;
      this.pid = pid;
      this.startedAt = startedAt;
      this.status = status == null || status.isBlank() ? "running" : status;
      this.healthUrl = normalizeHealthUrl(healthUrl);
      this.healthPort = normalizeHealthPort(healthPort);
      this.startupTimeoutSeconds = normalizeStartupTimeout(startupTimeoutSeconds);
      this.healthStatus = healthStatus == null ? "" : healthStatus;
    }

    static Entry attached(String appId, String command, List<String> commandParts, Path cwd,
        Process process, long startedAt, String healthUrl, int healthPort, int startupTimeoutSeconds) {
      return new Entry(appId, command, commandParts, cwd, process, process.pid(), startedAt, "running",
          healthUrl, healthPort, startupTimeoutSeconds, "");
    }

    static Entry detached(String appId, String command, List<String> commandParts, Path cwd,
        long pid, long startedAt, String status, String healthUrl, int healthPort,
        int startupTimeoutSeconds, String healthStatus) {
      return new Entry(appId, command, commandParts, cwd, null, pid, startedAt, status,
          healthUrl, healthPort, startupTimeoutSeconds, healthStatus);
    }

    boolean isAlive() {
      if (process != null) {
        return process.isAlive();
      }
      if (pid == UNKNOWN_PID) {
        return false;
      }
      return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    boolean hasHealthCheck() {
      return (healthUrl != null && !healthUrl.isBlank()) || healthPort > 0;
    }
  }
}
