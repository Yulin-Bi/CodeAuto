package com.codeauto.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.manage.ManagementStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

public class SkillService {
  private final Path cwd;
  private final ManagementStore managementStore;

  public SkillService(Path cwd) {
    this(cwd, new ManagementStore());
  }

  public SkillService(Path cwd, ManagementStore managementStore) {
    this.cwd = cwd.toAbsolutePath().normalize();
    this.managementStore = managementStore;
  }

  public List<SkillSummary> discover() {
    List<SkillSummary> skills = new ArrayList<>();
    addDirectorySkills(skills, cwd.resolve(".mini-code/skills"));
    addDirectorySkills(skills, cwd.resolve(".claude/skills"));
    addManagedSkills(skills);
    return skills;
  }

  public Optional<SkillSummary> find(String name) {
    return discover().stream().filter(skill -> skill.name().equals(name)).findFirst();
  }

  public String load(String name) throws Exception {
    Optional<SkillSummary> skill = find(name);
    if (skill.isEmpty()) {
      throw new IllegalArgumentException("Skill not found: " + name);
    }
    return Files.readString(skill.get().skillFile());
  }

  private static void addDirectorySkills(List<SkillSummary> skills, Path root) {
    if (!Files.isDirectory(root)) return;
    try (var stream = Files.list(root)) {
      stream.filter(Files::isDirectory).forEach(dir -> {
        Path skillFile = dir.resolve("SKILL.md");
        if (Files.exists(skillFile)) {
          skills.add(new SkillSummary(dir.getFileName().toString(), skillFile.toAbsolutePath().normalize()));
        }
      });
    } catch (Exception ignored) {
      // Discovery should not make startup brittle.
    }
  }

  private void addManagedSkills(List<SkillSummary> skills) {
    try {
      JsonNode config = managementStore.readSkills();
      for (Iterator<String> it = config.fieldNames(); it.hasNext();) {
        String name = it.next();
        Path path = Path.of(config.get(name).asText());
        Path skillFile = Files.isDirectory(path) ? path.resolve("SKILL.md") : path;
        if (Files.exists(skillFile)) {
          skills.add(new SkillSummary(name, skillFile.toAbsolutePath().normalize()));
        }
      }
    } catch (Exception ignored) {
      // Managed skill config is optional.
    }
  }
}
