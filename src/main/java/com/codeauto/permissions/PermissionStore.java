package com.codeauto.permissions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeauto.config.RuntimeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

public class PermissionStore {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Path path;

  public PermissionStore() {
    this(RuntimeConfig.homeDir().resolve("permissions.json"));
  }

  public PermissionStore(Path path) {
    this.path = path;
  }

  public Path path() {
    return path;
  }

  public Data read() {
    if (!Files.exists(path)) return new Data();
    try {
      return MAPPER.readValue(path.toFile(), Data.class);
    } catch (Exception ignored) {
      return new Data();
    }
  }

  public void write(Data data) {
    try {
      Files.createDirectories(path.getParent());
      // Write to temp file first, then atomically rename to prevent corruption on crash.
      Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
      Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data) + "\n");
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to write permissions: " + error.getMessage(), error);
    }
  }

  public static class Data {
    public Set<String> allowedDirectoryPrefixes = new HashSet<>();
    public Set<String> deniedDirectoryPrefixes = new HashSet<>();
    public Set<String> allowedCommandPatterns = new HashSet<>();
    public Set<String> deniedCommandPatterns = new HashSet<>();
    public Set<String> allowedEditPatterns = new HashSet<>();
    public Set<String> deniedEditPatterns = new HashSet<>();
  }
}
