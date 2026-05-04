package com.codeauto.skills;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionSkills {
  private static final ConcurrentHashMap<Path, LinkedHashMap<String, String>> loaded = new ConcurrentHashMap<>();

  private SessionSkills() {
  }

  public static void markLoaded(Path cwd, String name, String content) {
    loaded.computeIfAbsent(cwd.toAbsolutePath().normalize(), k -> new LinkedHashMap<>()).put(name, content);
  }

  public static Map<String, String> getLoaded(Path cwd) {
    var skills = loaded.get(cwd.toAbsolutePath().normalize());
    return skills != null ? Map.copyOf(skills) : Map.of();
  }

  public static Set<String> getLoadedNames(Path cwd) {
    var skills = loaded.get(cwd.toAbsolutePath().normalize());
    return skills != null ? Set.copyOf(skills.keySet()) : Set.of();
  }

  public static void clear(Path cwd) {
    loaded.remove(cwd.toAbsolutePath().normalize());
  }
}
