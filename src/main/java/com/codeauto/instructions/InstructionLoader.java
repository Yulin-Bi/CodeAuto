package com.codeauto.instructions;

import com.codeauto.config.RuntimeConfig;
import com.codeauto.memory.MemoryEntry;
import com.codeauto.memory.MemoryManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class InstructionLoader {
  private static final int MAX_MEMORIES = 5;

  public static String systemPrompt(Path cwd, String permissionSummary) {
    String base = "You are CodeAuto. Permissions: " + permissionSummary;
    List<InstructionFile> files = load(cwd);
    List<MemoryEntry> memories = new MemoryManager().relevant(cwd, "", MAX_MEMORIES);
    if (files.isEmpty() && memories.isEmpty()) return base;

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

  private static void appendMemories(StringBuilder prompt, List<MemoryEntry> memories) {
    Instant now = Instant.now();
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
