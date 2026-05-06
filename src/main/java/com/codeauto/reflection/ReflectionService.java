package com.codeauto.reflection;

import com.codeauto.config.RuntimeConfig;
import com.codeauto.core.AgentStep;
import com.codeauto.core.ChatMessage;
import com.codeauto.curator.Curator;
import com.codeauto.curator.Curator.BulletDelta;
import com.codeauto.memory.MemoryEntry;
import com.codeauto.memory.MemoryManager;
import com.codeauto.memory.MemoryType;
import com.codeauto.model.ModelAdapter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReflectionService {

  private static final Pattern BULLET_CITE = Pattern.compile("\\[bullet:([a-zA-Z0-9_-]+)\\]");
  private static final Map<String, Instant> lastBulletCreated = new ConcurrentHashMap<>();
  private static final Duration BULLET_COOLDOWN = Duration.ofMinutes(10);

  private static final String REFLECTION_SYSTEM_PROMPT = """
      You are a post-turn analyst for an AI coding agent. Review the turn below and extract lessons.

      Output in this EXACT Markdown structure:

      ### What Went Wrong
      [Describe specific failures, errors, or suboptimal outcomes. If nothing went wrong, write "Nothing."]

      ### Root Cause
      [The underlying reason. Was it a misunderstanding of the task? A bad tool choice? Missing context?]

      ### What Should Have Been Done Differently
      [The correct approach that would have avoided the problem.]

      ### Reusable Lesson
      [A concise, general rule that could help in future similar situations. 1-3 sentences.]

      ### Bullet Tags
      For each bullet cited in the turn (listed above as "Cited bullets"), tag it:
      [bullet:<id>]: helpful
      [bullet:<id>]: harmful
      Write "None." if no bullets were cited.

      Rules:
      - Be CONCISE. Focus on the most impactful lesson.
      - Write "Nothing." for sections with no content.
      - Output TEXT ONLY. Do NOT use tool calls. Do NOT output XML tags.
      - Frame the reusable lesson so it applies to similar future tasks, not just this one.
      - Tag a bullet as harmful if following it contributed to the error; helpful if it guided correct behavior.
      """;

  private ReflectionService() {}

  public static Optional<MemoryEntry> reflectIfNeeded(
      List<ChatMessage> messages, ModelAdapter model, Path cwd) {

    if (model == null || messages == null || messages.isEmpty()) return Optional.empty();

    ReflectionTrigger trigger = detectTrigger(messages);
    if (trigger == ReflectionTrigger.NONE) return Optional.empty();

    Set<String> citedBullets = extractCitations(messages);

    String reflection = generateReflection(model, messages, trigger, citedBullets);
    if (reflection == null || reflection.isBlank()) return Optional.empty();

    // Per-project storage: reflections and bullets go under <project>/.codeauto/
    // Regular memories remain in ~/.codeauto/memory/
    Path projectRoot = cwd != null ? cwd.resolve(".codeauto") : RuntimeConfig.homeDir();
    MemoryManager reflectionMemory = new MemoryManager(projectRoot.resolve("reflections"));
    MemoryManager bulletMemory = new MemoryManager(projectRoot.resolve("bullets"));

    MemoryEntry entry = reflectionMemory.save(
        MemoryType.FEEDBACK,
        buildReflectionTitle(trigger),
        cwd,
        List.of("reflection", "auto"),
        reflection);

    String lesson = extractReusableLesson(reflection);
    if (!lesson.isBlank()) {
      String section = sectionForTrigger(trigger);
      String key = (cwd == null ? "" : cwd.toAbsolutePath().normalize().toString()) + ":" + section;
      Instant last = lastBulletCreated.get(key);
      if (last == null || Duration.between(last, Instant.now()).compareTo(BULLET_COOLDOWN) > 0) {
        try {
          Curator curator = new Curator(bulletMemory);
          String bulletId = "ref-" + UUID.randomUUID().toString().substring(0, 6);
          String title = lesson.length() > 60 ? lesson.substring(0, 57) + "..." : lesson;
          curator.applyDeltas(cwd, List.of(
              new BulletDelta.Add(bulletId, title, lesson, section, List.of("reflection", "auto"))));
          lastBulletCreated.put(key, Instant.now());
        } catch (Exception ignored) {
          // curator is best-effort; reflection already saved
        }
      }
    }

    Map<String, String> bulletTags = parseBulletTags(reflection);
    if (!bulletTags.isEmpty()) {
      applyBulletTags(bulletMemory, bulletTags);
    } else if (citedBullets.size() == 1) {
      // fallback: only when a single bullet was cited — safe to infer from trigger
      String id = citedBullets.iterator().next();
      String tag = (trigger == ReflectionTrigger.TOOL_ERROR
          || trigger == ReflectionTrigger.USER_DISSATISFACTION) ? "harmful" : "helpful";
      applyBulletTags(bulletMemory, Map.of(id, tag));
    }
    // if multiple bullets cited but model didn't tag them, skip — too risky to guess

    return Optional.of(entry);
  }

  public enum ReflectionTrigger {
    TOOL_ERROR, MAX_STEPS, CANCELLED, USER_DISSATISFACTION, NONE
  }

  public static ReflectionTrigger detectTrigger(List<ChatMessage> messages) {
    boolean hasToolError = messages.stream()
        .filter(m -> m instanceof ChatMessage.ToolResultMessage)
        .map(m -> (ChatMessage.ToolResultMessage) m)
        .anyMatch(ChatMessage.ToolResultMessage::isError);
    if (hasToolError) return ReflectionTrigger.TOOL_ERROR;

    ChatMessage last = messages.getLast();
    if (last instanceof ChatMessage.AssistantMessage am
        && am.content() != null
        && am.content().contains("Reached maximum tool step limit")) {
      return ReflectionTrigger.MAX_STEPS;
    }

    if (last instanceof ChatMessage.AssistantMessage am
        && am.content() != null
        && am.content().equals("(Interrupted)")) {
      return ReflectionTrigger.CANCELLED;
    }

    for (int i = messages.size() - 1; i >= 0; i--) {
      ChatMessage msg = messages.get(i);
      if (msg instanceof ChatMessage.UserMessage um) {
        String lower = um.content().toLowerCase();
        if (lower.contains("wrong") || lower.contains("incorrect")
            || lower.contains("not what i") || lower.contains("doesn't work")
            || lower.contains("try again") || lower.contains("failed")) {
          return ReflectionTrigger.USER_DISSATISFACTION;
        }
        break;
      }
    }

    return ReflectionTrigger.NONE;
  }

  private static String generateReflection(
      ModelAdapter model, List<ChatMessage> messages, ReflectionTrigger trigger,
      Set<String> citedBullets) {

    List<ChatMessage> request = List.of(
        new ChatMessage.SystemMessage(REFLECTION_SYSTEM_PROMPT),
        buildReflectionUserMessage(messages, trigger, citedBullets));

    try {
      AgentStep step = model.next(request);
      if (step instanceof AgentStep.AssistantStep assistant) {
        String content = assistant.content();
        if (content != null && !content.isBlank()) return content.trim();
      }
    } catch (Exception ignored) {
      // model call failed -- skip reflection, never block
    }
    return null;
  }

  private static ChatMessage.UserMessage buildReflectionUserMessage(
      List<ChatMessage> messages, ReflectionTrigger trigger, Set<String> citedBullets) {

    StringBuilder sb = new StringBuilder();
    sb.append("Reflect on this completed agent turn.\n\n");
    sb.append("Turn ended because: ").append(triggerLabel(trigger)).append("\n\n");
    if (!citedBullets.isEmpty()) {
      sb.append("Cited bullets: ");
      for (String id : citedBullets) {
        sb.append("[bullet:").append(id).append("] ");
      }
      sb.append("\n\n");
    }
    sb.append("Conversation (most recent first):\n");

    int shown = 0;
    int maxToShow = 20;
    for (int i = messages.size() - 1; i >= 0 && shown < maxToShow; i--) {
      ChatMessage msg = messages.get(i);
      String formatted = formatForReflection(msg);
      if (formatted != null) {
        sb.append(formatted).append("\n");
        shown++;
      }
    }

    return new ChatMessage.UserMessage(sb.toString());
  }

  private static String formatForReflection(ChatMessage msg) {
    if (msg instanceof ChatMessage.UserMessage m) {
      return "[USER] " + truncate(m.content(), 500);
    } else if (msg instanceof ChatMessage.AssistantMessage m) {
      return "[ASSISTANT] " + truncate(m.content(), 500);
    } else if (msg instanceof ChatMessage.ToolResultMessage m) {
      String flag = m.isError() ? " [ERROR]" : "";
      return "[TOOL_RESULT: " + m.toolName() + flag + "] " + truncate(m.content(), 500);
    } else if (msg instanceof ChatMessage.AssistantToolCallMessage m) {
      return "[TOOL_CALL: " + m.toolName() + "] " + truncate(String.valueOf(m.input()), 300);
    }
    return null;
  }

  private static String triggerLabel(ReflectionTrigger trigger) {
    return switch (trigger) {
      case TOOL_ERROR -> "tool errors occurred";
      case MAX_STEPS -> "max tool steps reached";
      case CANCELLED -> "user cancelled the turn";
      case USER_DISSATISFACTION -> "user expressed dissatisfaction";
      default -> "unknown";
    };
  }

  private static String buildReflectionTitle(ReflectionTrigger trigger) {
    return switch (trigger) {
      case TOOL_ERROR -> "Reflection on tool errors";
      case MAX_STEPS -> "Reflection on incomplete turn";
      case CANCELLED -> "Reflection on cancelled turn";
      case USER_DISSATISFACTION -> "Reflection on user feedback";
      default -> "Reflection";
    };
  }

  public static Set<String> extractCitations(List<ChatMessage> messages) {
    Set<String> ids = new HashSet<>();
    for (ChatMessage msg : messages) {
      String haystack = null;
      if (msg instanceof ChatMessage.AssistantMessage m) {
        haystack = m.content();
      } else if (msg instanceof ChatMessage.AssistantToolCallMessage m) {
        haystack = String.valueOf(m.input());
      }
      if (haystack == null) continue;
      Matcher matcher = BULLET_CITE.matcher(haystack);
      while (matcher.find()) {
        ids.add(matcher.group(1));
      }
    }
    return ids;
  }

  public static Map<String, String> parseBulletTags(String reflection) {
    Map<String, String> tags = new HashMap<>();
    int start = reflection.indexOf("### Bullet Tags");
    if (start < 0) return tags;
    int headingEnd = reflection.indexOf('\n', start);
    if (headingEnd < 0) return tags;
    int nextHeading = reflection.indexOf("\n###", headingEnd + 1);
    String body = nextHeading > 0
        ? reflection.substring(headingEnd + 1, nextHeading)
        : reflection.substring(headingEnd + 1);
    for (String line : body.split("\\R")) {
      String trimmed = line.strip();
      if (trimmed.equals("None.") || trimmed.equals("None cited.")) break;
      Matcher m = BULLET_CITE.matcher(trimmed);
      if (m.find()) {
        String id = m.group(1);
        String tag = trimmed.substring(m.end()).replace(":", "").strip().toLowerCase();
        if (tag.equals("helpful") || tag.equals("harmful")) {
          tags.put(id, tag);
        }
      }
    }
    return tags;
  }

  private static void applyBulletTags(MemoryManager memory, Map<String, String> tags) {
    for (var entry : tags.entrySet()) {
      String id = entry.getKey();
      String tag = entry.getValue();
      if ("helpful".equals(tag)) {
        memory.incrementCounters(id, 1, 0);
      } else if ("harmful".equals(tag)) {
        memory.incrementCounters(id, 0, 1);
      }
    }
  }

  private static String truncate(String text, int maxLen) {
    if (text == null) return "";
    String normalized = text.replaceAll("\\s+", " ").trim();
    return normalized.length() > maxLen ? normalized.substring(0, maxLen) + "..." : normalized;
  }

  static String extractReusableLesson(String reflection) {
    int start = reflection.indexOf("### Reusable Lesson");
    if (start < 0) {
      start = reflection.indexOf("### Reusable");
    }
    if (start < 0) return "";
    int headingEnd = reflection.indexOf('\n', start);
    if (headingEnd < 0) return "";
    int nextHeading = reflection.indexOf("\n###", headingEnd + 1);
    String body = nextHeading > 0
        ? reflection.substring(headingEnd + 1, nextHeading)
        : reflection.substring(headingEnd + 1);
    String cleaned = body.strip();
    if (cleaned.equals("Nothing.") || cleaned.isBlank()) return "";
    return cleaned;
  }

  /** Clears the bullet cooldown map. For testing only. */
  public static void resetCooldowns() {
    lastBulletCreated.clear();
  }

  private static String sectionForTrigger(ReflectionTrigger trigger) {
    return switch (trigger) {
      case TOOL_ERROR -> "common_mistakes";
      case MAX_STEPS -> "strategies";
      case CANCELLED -> "common_mistakes";
      case USER_DISSATISFACTION -> "common_mistakes";
      default -> "general";
    };
  }
}
