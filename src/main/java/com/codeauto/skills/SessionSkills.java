package com.codeauto.skills;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionSkills {
  private static final ConcurrentHashMap<Path, LinkedHashMap<String, LinkedHashSet<String>>> loaded = new ConcurrentHashMap<>();

  private SessionSkills() {
  }

  public static void markLoaded(Path cwd, String name, Collection<String> groupIds) {
    LinkedHashSet<String> scopedGroups = new LinkedHashSet<>();
    if (groupIds != null) {
      for (String groupId : groupIds) {
        if (groupId != null && !groupId.isBlank()) {
          scopedGroups.add(groupId.trim());
        }
      }
    }
    loaded.computeIfAbsent(cwd.toAbsolutePath().normalize(), k -> new LinkedHashMap<>()).put(name, scopedGroups);
  }

  public static Set<String> getLoadedNames(Path cwd, Collection<String> activeGroupIds) {
    var skills = loaded.get(cwd.toAbsolutePath().normalize());
    if (skills == null || activeGroupIds == null || activeGroupIds.isEmpty()) {
      return Set.of();
    }
    LinkedHashSet<String> current = new LinkedHashSet<>();
    for (String groupId : activeGroupIds) {
      if (groupId != null && !groupId.isBlank()) {
        current.add(groupId.trim());
      }
    }
    if (current.isEmpty()) {
      return Set.of();
    }
    LinkedHashSet<String> names = new LinkedHashSet<>();
    for (var entry : skills.entrySet()) {
      for (String groupId : entry.getValue()) {
        if (current.contains(groupId)) {
          names.add(entry.getKey());
          break;
        }
      }
    }
    return Set.copyOf(names);
  }

  public static void clear(Path cwd) {
    loaded.remove(cwd.toAbsolutePath().normalize());
  }
}
