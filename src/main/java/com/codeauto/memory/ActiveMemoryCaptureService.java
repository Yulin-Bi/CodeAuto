package com.codeauto.memory;

import com.codeauto.config.RuntimeConfig;
import com.codeauto.core.ChatMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ActiveMemoryCaptureService {
  private static final int MAX_CANDIDATES_PER_TURN = 3;
  private final MemoryManager manager;

  public ActiveMemoryCaptureService() {
    this(new MemoryManager());
  }

  public ActiveMemoryCaptureService(MemoryManager manager) {
    this.manager = manager;
  }

  public List<MemoryCandidate> captureCandidates(Path cwd, List<ChatMessage> messages, int startIndex) {
    if (messages == null || messages.isEmpty()) return List.of();
    List<MemoryCandidate> candidates = new ArrayList<>();
    int from = Math.max(0, Math.min(startIndex, messages.size()));
    for (ChatMessage message : messages.subList(from, messages.size())) {
      if (candidates.size() >= MAX_CANDIDATES_PER_TURN) break;
      if (!(message instanceof ChatMessage.UserMessage user)) continue;
      MemoryCandidate candidate = candidate(user.content());
      if (candidate == null || duplicate(cwd, candidate)) continue;
      candidates.add(candidate);
    }
    return candidates;
  }

  public MemoryEntry saveToMemory(Path cwd, MemoryCandidate candidate) {
    return manager.save(candidate.type(), candidate.title(), cwd, candidate.tags(), candidate.content());
  }

  public Path saveToProjectClaude(Path cwd, MemoryCandidate candidate) throws Exception {
    if (cwd == null) throw new IllegalArgumentException("cwd is required");
    Path path = cwd.toAbsolutePath().normalize().resolve("CLAUDE.md");
    appendClaudeMemory(path, candidate);
    return path;
  }

  public Path saveToGlobalClaude(MemoryCandidate candidate) throws Exception {
    Path path = Path.of(System.getProperty("user.home"), ".claude", "CLAUDE.md");
    appendClaudeMemory(path, candidate);
    return path;
  }

  public Path saveToCodeAutoClaude(MemoryCandidate candidate) throws Exception {
    Path path = RuntimeConfig.homeDir().resolve("CLAUDE.md");
    appendClaudeMemory(path, candidate);
    return path;
  }

  private MemoryCandidate candidate(String raw) {
    String rawLower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    String content = normalizeContent(raw);
    if (content.length() < 8 || content.length() > 500) return null;
    String lower = content.toLowerCase(Locale.ROOT);

    if (containsAny(rawLower, "记住", "请记住", "帮我记住", "remember")) {
      return new MemoryCandidate(MemoryType.USER, title("User memory", content), content, List.of("active", "explicit"));
    }
    if (containsAny(lower, "以后都", "以后不要", "不要再", "我喜欢", "我不喜欢", "prefer", "don't use", "do not use")) {
      return new MemoryCandidate(MemoryType.USER, title("User preference", content), content, List.of("active", "preference"));
    }
    if (looksLikeProjectFact(lower)) {
      return new MemoryCandidate(MemoryType.PROJECT, title("Project note", content), content, List.of("active", "project"));
    }
    if (containsAny(lower, "决定采用", "决定使用", "架构决策", "we decided", "decision:")) {
      return new MemoryCandidate(MemoryType.PROJECT, title("Project decision", content), content, List.of("active", "decision"));
    }
    return null;
  }

  private static boolean looksLikeProjectFact(String lower) {
    boolean project = containsAny(lower, "本项目", "这个项目", "当前项目", "project");
    boolean fact = containsAny(lower,
        "使用", "采用", "测试命令", "构建命令", "启动命令", "部署", "约定",
        "uses", "test command", "build command", "start command", "convention");
    return project && fact;
  }

  private boolean duplicate(Path cwd, MemoryCandidate candidate) {
    String project = cwd == null ? "" : cwd.toAbsolutePath().normalize().toString();
    String normalized = normalizeForCompare(candidate.content());
    for (MemoryEntry entry : manager.list()) {
      if (!entry.project().isBlank() && !project.isBlank() && !entry.project().equals(project)) continue;
      String existing = normalizeForCompare(entry.content());
      if (similar(existing, normalized)) return true;
    }
    return containsClaudeMemory(cwd, candidate);
  }

  private static boolean containsClaudeMemory(Path cwd, MemoryCandidate candidate) {
    List<Path> paths = new ArrayList<>();
    paths.add(Path.of(System.getProperty("user.home"), ".claude", "CLAUDE.md"));
    paths.add(RuntimeConfig.homeDir().resolve("CLAUDE.md"));
    if (cwd != null) paths.add(cwd.toAbsolutePath().normalize().resolve("CLAUDE.md"));
    String normalized = normalizeForCompare(candidate.content());
    for (Path path : paths) {
      try {
        if (!Files.isRegularFile(path)) continue;
        if (similar(normalizeForCompare(Files.readString(path)), normalized)) return true;
      } catch (Exception ignored) {
        // Optional instruction files should not block memory capture.
      }
    }
    return false;
  }

  private static void appendClaudeMemory(Path path, MemoryCandidate candidate) throws Exception {
    Files.createDirectories(path.getParent());
    String existing = Files.isRegularFile(path) ? Files.readString(path) : "";
    if (similar(normalizeForCompare(existing), normalizeForCompare(candidate.content()))) return;
    StringBuilder entry = new StringBuilder();
    if (!existing.isBlank() && !existing.endsWith("\n")) entry.append("\n");
    entry.append("\n## CodeAuto Memory\n\n");
    entry.append("- ").append(candidate.content()).append("\n");
    Files.writeString(path, entry.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private static String normalizeContent(String raw) {
    if (raw == null) return "";
    String content = raw.replaceAll("\\s+", " ").trim();
    return content
        .replaceFirst("^(请)?帮我记住[：:,，\\s]*", "")
        .replaceFirst("^(请)?记住[：:,，\\s]*", "")
        .replaceFirst("(?i)^remember[：:,\\s]*", "")
        .trim();
  }

  private static String title(String prefix, String content) {
    String compact = content.replaceAll("\\s+", " ").trim();
    if (compact.length() > 56) compact = compact.substring(0, 56).trim() + "...";
    return prefix + ": " + compact;
  }

  private static String normalizeForCompare(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }

  private static boolean similar(String left, String right) {
    return !left.isBlank() && !right.isBlank()
        && (left.equals(right) || left.contains(right) || right.contains(left));
  }

  private static boolean containsAny(String value, String... needles) {
    for (String needle : needles) {
      if (value.contains(needle.toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  public record MemoryCandidate(MemoryType type, String title, String content, List<String> tags) {
  }
}
