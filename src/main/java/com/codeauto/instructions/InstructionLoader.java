package com.codeauto.instructions;

import com.codeauto.config.RuntimeConfig;
import com.codeauto.memory.MemoryEntry;
import com.codeauto.memory.MemoryManager;
import com.codeauto.memory.MemoryType;
import com.codeauto.skills.SessionSkills;
import com.codeauto.skills.SkillService;
import com.codeauto.todo.TodoStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class InstructionLoader {
  private static final int MAX_MEMORIES = 5;

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
        + "User preferences and personal style choices use type=user, destination=store — they become part of "
        + "your user profile (loaded in full each turn). "
        + "Project facts use destination=project (CLAUDE.md) or store with type=project.";
    List<InstructionFile> files = load(cwd);
    MemoryManager manager = new MemoryManager();
    List<MemoryEntry> userProfile = manager.loadUserProfile();
    // Exclude USER type — they're loaded separately as the full profile
    List<MemoryEntry> memories = manager.relevant(cwd, "", MAX_MEMORIES).stream()
        .filter(m -> !m.isBullet() && m.type() != MemoryType.USER)
        .toList();
    String todoSummary = cwd != null ? new TodoStore(cwd).summary() : "";
    var skillIndex = cwd != null
        ? new SkillService(cwd).index() : List.<com.codeauto.skills.SkillService.SkillIndexEntry>of();
    var loadedSkills = cwd != null ? SessionSkills.getLoaded(cwd) : java.util.Map.<String, String>of();

    boolean hasReminders = !files.isEmpty() || !memories.isEmpty() || !userProfile.isEmpty()
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
    if (!userProfile.isEmpty()) {
      prompt.append("\n# User Profile\n");
      prompt.append("These are your user's preferences, habits, and style choices. ");
      prompt.append("Always keep them in mind — they apply to every response.\n");
      for (MemoryEntry entry : userProfile) {
        prompt.append("\n## ").append(entry.title()).append("\n");
        prompt.append(entry.content().trim()).append("\n");
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
    if (cwd != null) {
      prompt.append("\n# Past experience\n");
      prompt.append("When you encounter an error or the user reports a problem, grep ");
      prompt.append(cwd.resolve(".codeauto/bullets").normalize().toString());
      prompt.append(" for compact one-line lessons from past mistakes. Each lesson has a [bullet:<id>] ");
      prompt.append("identifier and helpful/harmful counters. For full analysis of a particular past ");
      prompt.append("error, read the corresponding reflection in ");
      prompt.append(cwd.resolve(".codeauto/reflections").normalize().toString()).append(".\n");
      prompt.append("Cite relevant lessons as [bullet:<id>] in your response.\n");
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

  private static void appendMemories(StringBuilder prompt, List<MemoryEntry> memories) {
    Instant now = Instant.now();
    if (memories.isEmpty()) return;

    prompt.append("\n# Relevant persistent memories\n");
    prompt.append("Use these as helpful context. If a memory is marked stale, verify it before relying on it.\n");
    for (MemoryEntry memory : memories) {
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

  public record InstructionFile(String label, Path path, String content) {
  }
}
