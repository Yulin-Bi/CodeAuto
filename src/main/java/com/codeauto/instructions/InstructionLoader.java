package com.codeauto.instructions;

import com.codeauto.config.RuntimeConfig;
import com.codeauto.memory.MemoryEntry;
import com.codeauto.memory.MemoryManager;
import com.codeauto.skills.SessionSkills;
import com.codeauto.skills.SkillService;
import com.codeauto.todo.TodoStore;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class InstructionLoader {
  private static final int MAX_BULLETS_IN_PROMPT = 10;
  private static final int MAX_BULLET_INDEX_CHARS = 1400;
  private static final int HOT_BULLETS_LIMIT = 6;
  private static final int RELEVANT_BULLETS_LIMIT = 3;
  private static final int EXPLORATION_BULLETS_LIMIT = 1;
  private static final int SUMMARY_RELEVANCE_BOOST = 2;

  public static String systemPrompt(Path cwd, String permissionSummary) {
    String base = "You are CodeAuto. Permissions: " + permissionSummary
        + "\nTodo behavior: when the user gives a multi-step task (3+ distinct steps), use todo_create to break it "
        + "down into manageable items. Keep items for the same user task in one todo group. For a new plan, set a "
        + "concise groupTitle. Reuse the same groupId when you extend an unfinished plan in a later turn. Mark a "
        + "task as in_progress BEFORE starting work on it, and mark it completed IMMEDIATELY after finishing. Only "
        + "ONE task in_progress at a time. Use todo_list to review active groups at the start of each turn."
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
        + "User preferences and personal style choices use destination=store - they become part of "
        + "your user profile (loaded in full each turn). "
        + "Project facts and conventions use destination=project to write into the project's CLAUDE.md.";
    List<InstructionFile> files = load(cwd);
    MemoryManager manager = new MemoryManager();
    List<MemoryEntry> userProfile = manager.loadUserProfile();
    TodoStore todoStore = cwd != null ? new TodoStore(cwd) : null;
    String todoPromptContext = todoStore != null ? todoStore.promptContext() : "";
    List<String> bulletContextTerms = buildBulletContextTerms(cwd, todoStore);
    var skillIndex = cwd != null
        ? new SkillService(cwd).index() : List.<com.codeauto.skills.SkillService.SkillIndexEntry>of();
    var loadedSkills = cwd != null ? SessionSkills.getLoaded(cwd) : java.util.Map.<String, String>of();

    boolean hasReminders = !files.isEmpty() || !userProfile.isEmpty()
        || !todoPromptContext.isEmpty() || !skillIndex.isEmpty() || !loadedSkills.isEmpty() || cwd != null;
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
      prompt.append("Always keep them in mind - they apply to every response.\n");
      for (MemoryEntry entry : userProfile) {
        prompt.append("\n## ").append(entry.title()).append("\n");
        prompt.append(entry.content().trim()).append("\n");
      }
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
    if (cwd != null) {
      prompt.append("\n# Past experience\n");
      prompt.append("When you encounter an error or the user reports a problem, grep ");
      prompt.append(cwd.resolve(".codeauto/bullets").normalize().toString());
      prompt.append(" for compact one-line lessons from past mistakes. Each lesson has a [bullet:<id>] ");
      prompt.append("identifier plus lightweight state such as helpful/harmful/support counters. ");
      prompt.append("For full analysis of a particular past error, read the corresponding reflection in ");
      prompt.append(cwd.resolve(".codeauto/reflections").normalize().toString()).append(".\n");
      prompt.append("Some repeated-failure bullets also have canonical summaries under ");
      prompt.append(cwd.resolve(".codeauto/reflection-summaries").normalize().toString());
      prompt.append("; inspect them only when a bullet shows repeated support or the problem recurs. ");
      prompt.append("If a bullet line includes summaryPath=..., use read_file with that exact relative path.\n");
      prompt.append("Cite relevant lessons as [bullet:<id>] in your response.\n");
      MemoryManager bulletManager = new MemoryManager(cwd.resolve(".codeauto/bullets"));
      List<MemoryEntry> allBullets = bulletManager.list();
      int totalBullets = (int) allBullets.stream().filter(MemoryEntry::isBullet).count();
      List<MemoryEntry> bullets = selectBulletsForPrompt(cwd, allBullets, bulletContextTerms);
      recordPromptUsage(bulletManager, bullets);
      if (!bullets.isEmpty()) {
        prompt.append("\n## Bullet index (")
            .append(bullets.size()).append(" of ").append(totalBullets).append(")\n");
        int charBudget = 0;
        for (MemoryEntry bullet : bullets) {
          StringBuilder line = new StringBuilder();
          line.append("- [bullet:").append(bullet.bulletId()).append("] ");
          line.append(bullet.title());
          line.append(" (up ").append(bullet.helpfulCount())
              .append(", down ").append(bullet.harmfulCount())
              .append(", support ").append(bullet.supportCount());
          String summaryPath = summaryPath(bullet);
          if (hasSummary(cwd, bullet)) {
            line.append(", summaryPath=").append(summaryPath);
          }
          line.append(")");
          String tagText = promptTagText(bullet);
          if (!tagText.isBlank()) {
            line.append(" ").append(tagText);
          }
          line.append("\n");
          if (charBudget + line.length() > MAX_BULLET_INDEX_CHARS) {
            break;
          }
          prompt.append(line);
          charBudget += line.length();
        }
      }
    }
    if (!todoPromptContext.isEmpty()) {
      prompt.append("\n# Active todo groups\n").append(todoPromptContext).append("\n");
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

  private static List<MemoryEntry> selectBulletsForPrompt(
      Path cwd, List<MemoryEntry> entries, List<String> contextTerms) {
    List<MemoryEntry> bullets = entries.stream().filter(MemoryEntry::isBullet).toList();
    List<MemoryEntry> selected = new ArrayList<>();

    addUnique(selected, bullets.stream()
        .filter(MemoryEntry::isHot)
        .sorted(Comparator.comparingInt((MemoryEntry bullet) -> bulletPromptScore(cwd, bullet, contextTerms)).reversed()
            .thenComparing(MemoryEntry::updatedAt, Comparator.reverseOrder()))
        .limit(HOT_BULLETS_LIMIT)
        .toList());

    addUnique(selected, bullets.stream()
        .filter(bullet -> !bullet.isCold() && relevanceScore(bullet, contextTerms) > 0)
        .filter(bullet -> !containsBullet(selected, bullet))
        .sorted(Comparator.comparingInt((MemoryEntry bullet) -> relevanceScore(bullet, contextTerms)).reversed()
            .thenComparing(bullet -> bulletPromptScore(cwd, bullet, contextTerms), Comparator.reverseOrder())
            .thenComparing(MemoryEntry::updatedAt, Comparator.reverseOrder()))
        .limit(RELEVANT_BULLETS_LIMIT)
        .toList());

    addUnique(selected, bullets.stream()
        .filter(MemoryEntry::isWarm)
        .filter(bullet -> !containsBullet(selected, bullet))
        .sorted(Comparator.comparingInt((MemoryEntry bullet) -> explorationScore(cwd, bullet, contextTerms)).reversed()
            .thenComparing(MemoryEntry::updatedAt, Comparator.reverseOrder()))
        .limit(EXPLORATION_BULLETS_LIMIT)
        .toList());

    addUnique(selected, bullets.stream()
        .filter(bullet -> !bullet.isCold())
        .filter(bullet -> !containsBullet(selected, bullet))
        .sorted(Comparator.comparingInt((MemoryEntry bullet) -> bulletPromptScore(cwd, bullet, contextTerms)).reversed()
            .thenComparing(MemoryEntry::updatedAt, Comparator.reverseOrder()))
        .limit(Math.max(0, MAX_BULLETS_IN_PROMPT - selected.size()))
        .toList());

    return selected.stream().limit(MAX_BULLETS_IN_PROMPT).toList();
  }

  private static int bulletPromptScore(Path cwd, MemoryEntry bullet, List<String> contextTerms) {
    int quality = bullet.helpfulCount() * 2 - bullet.harmfulCount() * 3 + Math.min(4, bullet.supportCount());
    return quality + relevanceScore(bullet, contextTerms) + recencyScore(bullet)
        + summaryRelevanceBoost(cwd, bullet, contextTerms);
  }

  private static String promptTagText(MemoryEntry bullet) {
    List<String> tags = bullet.tags().stream()
        .filter(tag -> !tag.equals("reflection") && !tag.equals("auto"))
        .limit(3)
        .toList();
    if (tags.isEmpty()) {
      return "";
    }
    return "tags=" + String.join(",", tags);
  }

  private static int relevanceScore(MemoryEntry bullet, List<String> contextTerms) {
    if (contextTerms.isEmpty()) {
      return 0;
    }
    int score = 0;
    String title = bullet.title().toLowerCase(Locale.ROOT);
    String content = bullet.content().toLowerCase(Locale.ROOT);
    Set<String> tags = bullet.tags().stream()
        .map(tag -> tag.toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toSet());
    for (String term : contextTerms) {
      if (tags.contains(term)) {
        score += 5;
      }
      if (title.contains(term)) {
        score += 4;
      }
      if (content.contains(term)) {
        score += 2;
      }
    }
    return score;
  }

  private static int recencyScore(MemoryEntry bullet) {
    return recencyBonus(bullet.lastHelpfulAt(), 4, 2)
        + recencyBonus(bullet.lastRetrievedAt(), 2, 1);
  }

  private static int explorationScore(Path cwd, MemoryEntry bullet, List<String> contextTerms) {
    int score = Math.max(0, relevanceScore(bullet, contextTerms));
    score += Math.max(0, bullet.helpfulCount() - bullet.harmfulCount());
    score += summaryRelevanceBoost(cwd, bullet, contextTerms);
    if (bullet.retrieveCount() == 0) {
      score += 3;
    }
    if (bullet.lastInjectedAt().equals(Instant.EPOCH)) {
      score += 4;
    } else {
      long ageDays = Math.min(14, java.time.Duration.between(bullet.lastInjectedAt(), Instant.now()).toDays());
      score += (int) ageDays;
    }
    return score;
  }

  private static int recencyBonus(Instant when, int recentBonus, int olderBonus) {
    if (when == null || when.equals(Instant.EPOCH)) {
      return 0;
    }
    long days = java.time.Duration.between(when, Instant.now()).toDays();
    if (days <= 7) {
      return recentBonus;
    }
    if (days <= 30) {
      return olderBonus;
    }
    return 0;
  }

  private static List<String> buildBulletContextTerms(Path cwd, TodoStore todoStore) {
    LinkedHashSet<String> terms = new LinkedHashSet<>();
    if (cwd != null && cwd.getFileName() != null) {
      addTerms(terms, cwd.getFileName().toString());
    }
    if (todoStore != null) {
      for (String text : todoStore.activeContextTexts()) {
        addTerms(terms, text);
      }
    }
    return new ArrayList<>(terms);
  }

  private static void addTerms(Set<String> terms, String text) {
    if (text == null || text.isBlank()) {
      return;
    }
    for (String part : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}_-]+")) {
      if (part.length() >= 3) {
        terms.add(part);
      }
    }
  }

  private static void addUnique(List<MemoryEntry> target, List<MemoryEntry> candidates) {
    for (MemoryEntry candidate : candidates) {
      if (target.size() >= MAX_BULLETS_IN_PROMPT) {
        return;
      }
      if (!containsBullet(target, candidate)) {
        target.add(candidate);
      }
    }
  }

  private static boolean containsBullet(List<MemoryEntry> entries, MemoryEntry candidate) {
    return entries.stream().anyMatch(entry -> entry.bulletId().equals(candidate.bulletId()));
  }

  private static void recordPromptUsage(MemoryManager bulletManager, List<MemoryEntry> bullets) {
    for (MemoryEntry bullet : bullets) {
      bulletManager.recordRetrieval(bullet.bulletId());
      bulletManager.recordInjection(bullet.bulletId());
    }
  }

  private static boolean hasSummary(Path cwd, MemoryEntry bullet) {
    if (cwd == null || bullet == null || !bullet.isBullet()) {
      return false;
    }
    Path summary = cwd.resolve(summaryPath(bullet));
    return Files.isRegularFile(summary);
  }

  private static String summaryPath(MemoryEntry bullet) {
    return ".codeauto/reflection-summaries/" + MemoryEntry.normalizeBulletId(bullet.bulletId()) + ".md";
  }

  private static int summaryRelevanceBoost(Path cwd, MemoryEntry bullet, List<String> contextTerms) {
    return relevanceScore(bullet, contextTerms) > 0 && hasSummary(cwd, bullet) ? SUMMARY_RELEVANCE_BOOST : 0;
  }

  public record InstructionFile(String label, Path path, String content) {
  }
}
