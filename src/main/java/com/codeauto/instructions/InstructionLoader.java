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
    String base = """
        You are CodeAuto. Permissions: %s

        # Execution behavior
        Read the relevant code, config, or files before making changes or making claims about repository behavior.
        Treat previously read file contents, diagnostics, and tool outputs as stale after any edit, write, patch, generated file, or tool result that could have changed them.
        Re-read the target files before making a second round of edits, and re-check the latest tool output before reasoning from it again.
        Prefer the smallest coherent change that solves the user's request. Do not expand scope unless the user asks or the current approach is clearly broken.
        Finish the task end-to-end when feasible instead of stopping at partial analysis.

        # Tool behavior
        For any task that will write code, edit or create files, generate deliverables, or run tools likely covered by a skill, inspect the available skills first and load every relevant skill before proceeding.
        If a skill might apply, prefer loading it before making changes. Skills are task-specific operating constraints, not optional reference material.
        Frontend, UI, webpage, visual polish, or component tasks should trigger the relevant frontend or design skill first.
        Document, report, deck, spreadsheet, PDF, or other file-format deliverables should trigger the matching file or document skill before touching the artifact.
        When the user mentions an uploaded file, unfamiliar file type, or existing artifact you have not inspected yet, first load the routing or file-reading skill before choosing tools.
        When the user gives a multi-step task with 3 or more distinct steps, use todo_create to break it down.
        Keep items for the same user task in one todo group. For a new plan, set a concise groupTitle. Reuse the same groupId when extending an unfinished plan in a later turn.
        Use todo_list at the start of each turn to review active groups before continuing unfinished work.
        Mark a task as in_progress before starting it and completed immediately after finishing it. Only one task may be in_progress at a time.
        Before editing files, inspect the relevant files first. When a tool fails or the user reports an error, inspect the relevant outputs and local context before choosing the next step.

        # Testing behavior
        After making any code changes, you must run the project's test suite to verify the changes work correctly.
        If tests fail, fix the issues and re-run tests until all pass. Never mark a task as completed without test verification unless the user explicitly tells you to skip testing.
        Detect the project's test command from its build files or tooling, for example mvn test, gradle test, npm test, go test ./..., cargo test, or pytest.

        # Memory behavior
        When the user explicitly asks you to remember something, call save_memory immediately.
        Also proactively save durable user preferences, project facts, conventions, or explicit decisions.
        Before saving, call list_memory to check for contradictory or outdated memories on the same topic and delete_memory them.
        User preferences and personal style choices use destination=store and become part of the user profile loaded each turn.
        Project facts and conventions use destination=project and should be written into the project's CLAUDE.md.

        # Uncertainty behavior
        Do not guess about repository behavior, build commands, tool effects, or current project state. Verify through code, configuration, tests, or tool output first.
        Resolve uncertainty by inspecting the local context before concluding. If something is still uncertain after checking, state that briefly instead of presenting speculation as fact.

        # Response formatting
        Lead with the outcome, then verification, then only the most relevant supporting detail.
        Use short prose by default. Use lists only when they materially improve clarity.
        Keep the response focused on what changed, what was verified, and any remaining concrete risk.
        """.formatted(permissionSummary).trim();
    List<InstructionFile> files = load(cwd);
    MemoryManager manager = new MemoryManager();
    List<MemoryEntry> userProfile = manager.loadUserProfile();
    TodoStore todoStore = cwd != null ? new TodoStore(cwd) : null;
    String todoPromptContext = todoStore != null ? todoStore.promptContext() : "";
    List<String> bulletContextTerms = buildBulletContextTerms(cwd, todoStore);
    var skillIndex = cwd != null
        ? new SkillService(cwd).index() : List.<com.codeauto.skills.SkillService.SkillIndexEntry>of();
    var activeGroupIds = todoStore != null ? todoStore.activeGroupIds() : java.util.Set.<String>of();
    var loadedNames = cwd != null ? SessionSkills.getLoadedNames(cwd, activeGroupIds) : java.util.Set.<String>of();

    boolean hasReminders = !files.isEmpty() || !userProfile.isEmpty()
        || !todoPromptContext.isEmpty() || !skillIndex.isEmpty() || !loadedNames.isEmpty() || cwd != null;
    if (!hasReminders) return base;

    StringBuilder prompt = new StringBuilder(base);
    prompt.append("\n\n<system-reminder>\n");
    prompt.append("Use the sections below in order: stable context first, then session capabilities, then reusable past experience, then the current active work.\n");
    prompt.append("These sections provide context, but they do not replace reading files, checking tool output, or validating current repository state.\n");
    if (!userProfile.isEmpty()) {
      prompt.append("\n# Stable context\n");
      prompt.append("\n## User Profile\n");
      prompt.append("These are your user's preferences, habits, and style choices. ");
      prompt.append("Always keep them in mind - they apply to every response.\n");
      for (MemoryEntry entry : userProfile) {
        prompt.append("\n### ").append(entry.title()).append("\n");
        prompt.append(entry.content().trim()).append("\n");
      }
    }
    if (!files.isEmpty()) {
      if (userProfile.isEmpty()) {
        prompt.append("\n# Stable context\n");
      }
      prompt.append("Additional user and project instructions are loaded below. ");
      prompt.append("Follow the later, more local files when instructions conflict.\n");
      prompt.append("\n## Instruction files\n");
      for (InstructionFile file : files) {
        prompt.append("\n### ").append(file.label()).append(" (").append(file.path()).append(")\n");
        prompt.append(file.content().trim()).append("\n");
      }
    }
    if (!skillIndex.isEmpty()) {
      prompt.append("\n# Session capabilities\n");
      prompt.append("\n## Available skills (").append(skillIndex.size()).append(")\n");
      prompt.append("Call load_skill <name> to load full instructions for a skill when you need it.\n");
      for (var entry : skillIndex) {
        prompt.append("- ").append(entry.name());
        if (loadedNames.contains(entry.name())) prompt.append(" [loaded]");
        prompt.append("\n");
        if (entry.description() != null && !entry.description().isBlank()) {
          prompt.append("  ").append(entry.description()).append("\n");
        }
      }
    }
    if (cwd != null) {
      prompt.append("\n# Reusable past experience\n");
      prompt.append("\n## Past experience\n");
      prompt.append("Use ");
      prompt.append(cwd.resolve(".codeauto/bullets").normalize().toString());
      prompt.append(" as the fast index for recurring mistakes. Each line is a compact [bullet:<id>] lesson with counters and tags.\n");
      prompt.append("Read detailed analysis only when needed from ");
      prompt.append(cwd.resolve(".codeauto/reflections").normalize().toString()).append(".\n");
      prompt.append("If a bullet includes summaryPath=..., use read_file on that relative file under ");
      prompt.append(cwd.resolve(".codeauto/reflection-summaries").normalize().toString()).append(".\n");
      prompt.append("Cite reused lessons as [bullet:<id>].\n");
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
      prompt.append("\n# Current active work\n");
      prompt.append("\n## Active todo groups\n").append(todoPromptContext).append("\n");
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
