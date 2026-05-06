package com.codeauto.instructions;

import com.codeauto.config.RuntimeConfig;
import com.codeauto.memory.MemoryEntry;
import com.codeauto.memory.MemoryManager;
import com.codeauto.skills.SessionSkills;
import com.codeauto.skills.SkillService;
import com.codeauto.todo.TodoStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class InstructionLoader {
  private static final int MAX_MEMORIES = 5;
  private static final int MAX_BULLETS = 10;

  public static String systemPrompt(Path cwd, String permissionSummary) {
    String base = "You are CodeAuto. Permissions: " + permissionSummary
        + "\nTodo behavior: when the user gives a multi-step task (3+ distinct steps), use todo_create to break it "
        + "down into manageable items. Mark a task as in_progress BEFORE starting work on it, and mark it completed "
        + "IMMEDIATELY after finishing. Only ONE task in_progress at a time. "
        + "Use todo_list to review what's left to do at the start of each turn."
        + "\nTesting behavior: after making any code changes, you MUST run the project's test suite to verify your "
        + "changes work correctly. If tests fail, fix the issues and re-run tests until all pass. Never mark a task "
        + "as completed without test verification, unless the user explicitly tells you to skip testing. Detect the "
        + "project's test command from its build files (e.g., 'mvn test' for Maven, 'gradle test' for Gradle, "
        + "'npm test' for Node, 'go test ./...' for Go, 'cargo test' for Rust, 'pytest' for Python)."
        + "\nMemory behavior: when the user explicitly asks you to remember something, call save_memory immediately. "
        + "Also proactively save when the user states a preference (\"I prefer...\", \"I don't like...\", "
        + "\"always use...\", \"never use...\"), a project fact (build commands, conventions, tech stack), "
        + "or a decision (\"let's use X instead of Y\"). Before saving, call list_memory to check for "
        + "contradictory or outdated memories on the same topic and delete_memory them. "
        + "Always ask the user which destination they prefer: store, project, global, or codeauto.";
    List<InstructionFile> files = load(cwd);
    MemoryManager manager = new MemoryManager();
    List<MemoryEntry> regulars = manager.relevant(cwd, "", MAX_MEMORIES).stream()
        .filter(m -> !m.isBullet())
        .toList();
    List<MemoryEntry> bullets = fetchRelevantBullets(cwd);
    List<MemoryEntry> memories = new ArrayList<>(regulars.size() + bullets.size());
    memories.addAll(regulars);
    memories.addAll(bullets);
    String todoSummary = cwd != null ? new TodoStore(cwd).summary() : "";
    var skillIndex = cwd != null
        ? new SkillService(cwd).index() : List.<com.codeauto.skills.SkillService.SkillIndexEntry>of();
    var loadedSkills = cwd != null ? SessionSkills.getLoaded(cwd) : java.util.Map.<String, String>of();

    boolean hasReminders = !files.isEmpty() || !memories.isEmpty()
        || !todoSummary.isEmpty() || !skillIndex.isEmpty() || !loadedSkills.isEmpty();
    if (!hasReminders) return base;

    StringBuilder prompt = new StringBuilder(base);
    prompt.append("\n\n<system-reminder>\n");
    if (!files.isEmpty()) {
      prompt.append("Additional user and project instructions are loaded below. ");
      prompt.append("Follow the later, more local files when instructions conflict.\n");
      for (InstructionFile file : files) {
        prompt.append("\n# ").append(file.label()).append(" (").append(file.path()).append(")\n");
        prompt.append(file.content().trim()).append("\n");
      }
    }
    if (!todoSummary.isEmpty()) {
      prompt.append("\n# Todo summary\n").append(todoSummary).append("\n");
    }
    if (!skillIndex.isEmpty()) {
      prompt.append("\n# Available skills (").append(skillIndex.size()).append(")\n");
      prompt.append("Call load_skill <name> to load full instructions for a skill when you need it.\n");
      var loadedNames = SessionSkills.getLoadedNames(cwd);
      for (var entry : skillIndex) {
        prompt.append("- ").append(entry.name());
        if (loadedNames.contains(entry.name())) prompt.append(" [loaded]");
        prompt.append("\n");
        if (entry.description() != null && !entry.description().isBlank()) {
          prompt.append("  ").append(entry.description()).append("\n");
        }
      }
    }
    if (!loadedSkills.isEmpty()) {
      prompt.append("\n# Loaded skill instructions\n");
      prompt.append("These skills have been loaded and their instructions apply for the rest of this session.\n");
      for (var entry : loadedSkills.entrySet()) {
        prompt.append("\n## ").append(entry.getKey()).append("\n");
        prompt.append(entry.getValue().trim()).append("\n");
      }
    }
    if (!memories.isEmpty()) {
      appendMemories(prompt, memories);
    }
    prompt.append("</system-reminder>");
    return prompt.toString();
  }

  public static List<InstructionFile> load(Path cwd) {
    List<InstructionFile> result = new ArrayList<>();
    addIfPresent(result, "user", Path.of(System.getProperty("user.home"), ".claude", "CLAUDE.md"));
    addIfPresent(result, "codeauto", RuntimeConfig.homeDir().resolve("CLAUDE.md"));
    if (cwd != null) {
      Path root = cwd.toAbsolutePath().normalize();
      addIfPresent(result, "project", root.resolve("CLAUDE.md"));
      addIfPresent(result, "project-local", root.resolve("CLAUDE.local.md"));
    }
    return result;
  }

  private static void addIfPresent(List<InstructionFile> result, String label, Path path) {
    try {
      if (Files.isRegularFile(path)) {
        String content = Files.readString(path);
        if (!content.isBlank()) {
          result.add(new InstructionFile(label, path.toAbsolutePath().normalize(), content));
        }
      }
    } catch (Exception ignored) {
      // Instruction files are optional and should never block startup.
    }
  }

  private static List<MemoryEntry> fetchRelevantBullets(Path cwd) {
    List<MemoryEntry> allBullets = new ArrayList<>();

    // Project-specific bullets: <cwd>/.codeauto/bullets/
    if (cwd != null) {
      Path projectBulletsDir = cwd.resolve(".codeauto").resolve("bullets");
      if (Files.isDirectory(projectBulletsDir)) {
        allBullets.addAll(new MemoryManager(projectBulletsDir).list());
      }
    }
    // Global bullets: ~/.codeauto/bullets/
    Path globalBulletsDir = RuntimeConfig.homeDir().resolve("bullets");
    if (Files.isDirectory(globalBulletsDir)) {
      allBullets.addAll(new MemoryManager(globalBulletsDir).list());
    }

    String project = cwd == null ? "" : cwd.toAbsolutePath().normalize().toString();
    return allBullets.stream()
        .filter(MemoryEntry::isBullet)
        .filter(b -> project.isBlank() || b.project().isBlank() || b.project().equals(project))
        .sorted(Comparator.comparing((MemoryEntry b) -> {
          if (project.isBlank()) return 0;
          return b.project().equals(project) ? 0 : 1;
        }).thenComparing(MemoryEntry::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(MAX_BULLETS)
        .toList();
  }

  private static void appendMemories(StringBuilder prompt, List<MemoryEntry> memories) {
    Instant now = Instant.now();
    List<MemoryEntry> bullets = memories.stream().filter(MemoryEntry::isBullet).toList();
    List<MemoryEntry> regulars = memories.stream().filter(m -> !m.isBullet()).toList();

    if (!regulars.isEmpty()) {
      prompt.append("\n# Relevant persistent memories\n");
      prompt.append("Use these as helpful context. If a memory is marked stale, verify it before relying on it.\n");
      for (MemoryEntry memory : regulars) {
        prompt.append("\n## ").append(memory.title())
            .append(" [").append(memory.type().name().toLowerCase()).append("]");
        if (memory.stale(now)) prompt.append(" [stale]");
        prompt.append("\n");
        if (!memory.tags().isEmpty()) {
          prompt.append("tags: ").append(String.join(", ", memory.tags())).append("\n");
        }
        prompt.append(memory.content().trim()).append("\n");
      }
    }

    if (!bullets.isEmpty()) {
      prompt.append("\n# ACE Playbook (").append(bullets.size()).append(")\n");
      prompt.append("Each bullet below is a one-line lesson from past experience. ");
      prompt.append("Apply them when the situation matches. Cite as [bullet:<id>].\n");
      for (MemoryEntry bullet : bullets) {
        prompt.append("- [bullet:").append(bullet.bulletId()).append("]");
        if (!bullet.section().isBlank()) {
          prompt.append(" [").append(bullet.section()).append("]");
        }
        if (bullet.helpfulCount() > 0 || bullet.harmfulCount() > 0) {
          prompt.append(" +").append(bullet.helpfulCount()).append("/-").append(bullet.harmfulCount());
        }
        prompt.append(" ").append(bullet.content().trim());
        if (bullet.stale(now)) prompt.append(" [stale]");
        prompt.append("\n");
      }
    }
  }

  public record InstructionFile(String label, Path path, String content) {
  }
}
