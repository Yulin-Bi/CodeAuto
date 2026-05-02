package com.codeauto.permissions;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class PermissionManager {
  private final Path workspaceRoot;
  private final PermissionStore store;
  private final PermissionPrompt prompt;
  private final Set<Path> allowedPaths = new HashSet<>();
  private final Set<Path> deniedPaths = new HashSet<>();
  private final Set<String> deniedCommands = new HashSet<>();
  private final Set<String> allowedCommands = new HashSet<>();
  private final Set<String> deniedCommandRules = new HashSet<>();
  private final Set<String> allowedCommandRules = new HashSet<>();
  private final Set<String> turnAllowedCommands = new HashSet<>();
  private final Set<Path> allowedEdits = new HashSet<>();
  private final Set<Path> deniedEdits = new HashSet<>();
  private final Set<String> allowedEditRules = new HashSet<>();
  private final Set<String> deniedEditRules = new HashSet<>();
  private final Set<Path> turnAllowedEdits = new HashSet<>();
  private boolean allowAllEditsThisTurn;
  private String lastDenialFeedback;

  public PermissionManager(Path workspaceRoot) {
    this(workspaceRoot, new PermissionStore(), new ConsolePermissionPrompt());
  }

  public PermissionManager(Path workspaceRoot, PermissionStore store, PermissionPrompt prompt) {
    this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    this.store = store;
    this.prompt = prompt;
    this.allowedPaths.add(this.workspaceRoot);
    loadStore();
  }

  public void beginTurn() {
    allowAllEditsThisTurn = false;
    turnAllowedCommands.clear();
    turnAllowedEdits.clear();
    lastDenialFeedback = null;
  }

  public void endTurn() {
    allowAllEditsThisTurn = false;
    turnAllowedCommands.clear();
    turnAllowedEdits.clear();
    lastDenialFeedback = null;
  }

  public boolean canRead(Path path) {
    Path normalized = normalize(path);
    return !matchesPath(normalized, deniedPaths) && matchesPath(normalized, allowedPaths);
  }

  public boolean canWrite(Path path) {
    Path normalized = normalize(path);
    if (matchesPath(normalized, deniedEdits) || matchesToolRule("Edit", workspaceRelative(normalized), deniedEditRules)) return false;
    if (allowAllEditsThisTurn
        || matchesPath(normalized, turnAllowedEdits)
        || matchesPath(normalized, allowedEdits)
        || matchesToolRule("Edit", workspaceRelative(normalized), allowedEditRules)) return true;
    if (!canRead(normalized)) return false;
    PermissionResponse response = prompt.askDetailed(new PermissionRequest(
        "edit",
        "Allow edit to " + normalized + "?",
        normalized.toString(),
        List.of(PermissionDecision.ALLOW_ONCE, PermissionDecision.ALLOW_ALWAYS, PermissionDecision.ALLOW_TURN,
            PermissionDecision.DENY_ONCE, PermissionDecision.DENY_ALWAYS, PermissionDecision.DENY_WITH_FEEDBACK)));
    PermissionDecision decision = response.decision();
    rememberFeedback(response);
    applyEditDecision(normalized, decision);
    return decision == PermissionDecision.ALLOW_ONCE || decision == PermissionDecision.ALLOW_ALWAYS
        || decision == PermissionDecision.ALLOW_TURN || decision == PermissionDecision.ALLOW_ALL_TURN;
  }

  public boolean canRun(String command, List<String> args) {
    String signature = String.join(" ", prepend(command, args));
    if (turnAllowedCommands.contains(signature)
        || allowedCommands.contains(signature)
        || matchesToolRule("Bash", signature, allowedCommandRules)) {
      return true;
    }
    if (deniedCommands.contains(signature) || matchesToolRule("Bash", signature, deniedCommandRules)) {
      return false;
    }
    String danger = classifyDangerousCommand(command, args);
    if (danger == null) return true;
    PermissionResponse response = prompt.askDetailed(new PermissionRequest(
        "command",
        "Allow command? " + danger,
        signature,
        List.of(PermissionDecision.ALLOW_ONCE, PermissionDecision.ALLOW_ALWAYS, PermissionDecision.ALLOW_TURN,
            PermissionDecision.DENY_ONCE, PermissionDecision.DENY_ALWAYS, PermissionDecision.DENY_WITH_FEEDBACK)));
    PermissionDecision decision = response.decision();
    rememberFeedback(response);
    applyCommandDecision(signature, decision);
    return decision == PermissionDecision.ALLOW_ONCE || decision == PermissionDecision.ALLOW_ALWAYS
        || decision == PermissionDecision.ALLOW_TURN;
  }

  public String classifyDangerousCommand(String command, List<String> args) {
    if ("git".equals(command)) {
      if (args.contains("reset") && args.contains("--hard")) return "git reset --hard can discard local changes";
      if (args.contains("clean")) return "git clean can delete untracked files";
      if (args.contains("checkout") && args.contains("--")) return "git checkout -- can overwrite files";
      if (args.contains("restore") && args.stream().anyMatch(arg -> arg.startsWith("--source"))) return "git restore --source can overwrite files";
      if (args.contains("push") && args.stream().anyMatch(arg -> arg.equals("--force") || arg.equals("-f"))) return "git push --force rewrites remote history";
    }
    if ("npm".equals(command) && args.contains("publish")) return "npm publish affects a registry";
    if (List.of("node", "python", "python3", "bash", "sh", "cmd", "pwsh", "powershell").contains(command)) {
      return command + " can execute arbitrary local code";
    }
    return null;
  }

  public String summary() {
    return "workspace=" + workspaceRoot + ", allowedCommands=" + allowedCommands.size()
        + ", allowedEdits=" + allowedEdits.size();
  }

  public String describePermissions() {
    PermissionStore.Data data = store.read();
    return "permissions=" + store.path().toAbsolutePath().normalize()
        + "\nworkspace=" + workspaceRoot
        + "\nallowedDirectoryPrefixes=" + data.allowedDirectoryPrefixes.size()
        + "\ndeniedDirectoryPrefixes=" + data.deniedDirectoryPrefixes.size()
        + "\nallowedCommandPatterns=" + data.allowedCommandPatterns.size()
        + "\ndeniedCommandPatterns=" + data.deniedCommandPatterns.size()
        + "\nallowedEditPatterns=" + data.allowedEditPatterns.size()
        + "\ndeniedEditPatterns=" + data.deniedEditPatterns.size()
        + "\nturnAllowedCommands=" + turnAllowedCommands.size()
        + "\nturnAllowedEdits=" + turnAllowedEdits.size()
        + "\nallowAllEditsThisTurn=" + allowAllEditsThisTurn;
  }

  public String consumeLastDenialFeedback() {
    String feedback = lastDenialFeedback;
    lastDenialFeedback = null;
    return feedback;
  }

  public String formatLastDenialFeedback() {
    String feedback = consumeLastDenialFeedback();
    return feedback == null || feedback.isBlank() ? "" : "\nUser feedback: " + feedback.trim();
  }

  private void rememberFeedback(PermissionResponse response) {
    if (response.decision() == PermissionDecision.DENY_WITH_FEEDBACK) {
      lastDenialFeedback = response.feedback();
    }
  }

  private void loadStore() {
    PermissionStore.Data data = store.read();
    data.allowedDirectoryPrefixes.stream().map(Path::of).map(this::normalize).forEach(allowedPaths::add);
    data.deniedDirectoryPrefixes.stream().map(Path::of).map(this::normalize).forEach(deniedPaths::add);
    splitCommandPatterns(data.allowedCommandPatterns, allowedCommands, allowedCommandRules);
    splitCommandPatterns(data.deniedCommandPatterns, deniedCommands, deniedCommandRules);
    splitPathPatterns(data.allowedEditPatterns, allowedEdits, allowedEditRules);
    splitPathPatterns(data.deniedEditPatterns, deniedEdits, deniedEditRules);
  }

  private void persist() {
    PermissionStore.Data data = new PermissionStore.Data();
    allowedPaths.stream().map(Path::toString).forEach(data.allowedDirectoryPrefixes::add);
    deniedPaths.stream().map(Path::toString).forEach(data.deniedDirectoryPrefixes::add);
    data.allowedCommandPatterns.addAll(allowedCommands);
    data.allowedCommandPatterns.addAll(allowedCommandRules);
    data.deniedCommandPatterns.addAll(deniedCommands);
    data.deniedCommandPatterns.addAll(deniedCommandRules);
    allowedEdits.stream().map(Path::toString).forEach(data.allowedEditPatterns::add);
    data.allowedEditPatterns.addAll(allowedEditRules);
    deniedEdits.stream().map(Path::toString).forEach(data.deniedEditPatterns::add);
    data.deniedEditPatterns.addAll(deniedEditRules);
    store.write(data);
  }

  private void applyCommandDecision(String signature, PermissionDecision decision) {
    switch (decision) {
      case ALLOW_ALWAYS -> {
        allowedCommands.add(signature);
        persist();
      }
      case ALLOW_TURN -> turnAllowedCommands.add(signature);
      case DENY_ALWAYS -> {
        deniedCommands.add(signature);
        persist();
      }
      default -> {
      }
    }
  }

  private void applyEditDecision(Path path, PermissionDecision decision) {
    switch (decision) {
      case ALLOW_ALWAYS -> {
        allowedEdits.add(path);
        persist();
      }
      case ALLOW_TURN -> turnAllowedEdits.add(path);
      case ALLOW_ALL_TURN -> allowAllEditsThisTurn = true;
      case DENY_ALWAYS -> {
        deniedEdits.add(path);
        persist();
      }
      default -> {
      }
    }
  }

  private Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private boolean matchesPath(Path normalized, Set<Path> roots) {
    return roots.stream().anyMatch(normalized::startsWith);
  }

  private String workspaceRelative(Path normalized) {
    Path absolute = normalized.toAbsolutePath().normalize();
    if (absolute.startsWith(workspaceRoot)) {
      return workspaceRoot.relativize(absolute).toString().replace('\\', '/');
    }
    return absolute.toString().replace('\\', '/');
  }

  private void splitCommandPatterns(Set<String> raw, Set<String> exact, Set<String> rules) {
    for (String pattern : raw) {
      if (isRulePattern(pattern) || hasWildcard(pattern)) {
        rules.add(pattern);
      } else {
        exact.add(pattern);
      }
    }
  }

  private void splitPathPatterns(Set<String> raw, Set<Path> exact, Set<String> rules) {
    for (String pattern : raw) {
      if (isRulePattern(pattern) || hasWildcard(pattern)) {
        rules.add(pattern);
      } else {
        exact.add(normalize(Path.of(pattern)));
      }
    }
  }

  private static boolean matchesToolRule(String defaultToolName, String value, Set<String> rules) {
    String normalizedValue = value.replace('\\', '/');
    for (String rule : rules) {
      Rule parsed = parseRule(rule, defaultToolName);
      if (!toolNameMatches(defaultToolName, parsed.toolName()) || !globMatches(parsed.specifier(), normalizedValue)) {
        continue;
      }
      return true;
    }
    return false;
  }

  private static Rule parseRule(String raw, String defaultToolName) {
    String trimmed = raw == null ? "" : raw.trim();
    int open = trimmed.indexOf('(');
    if (open > 0 && trimmed.endsWith(")")) {
      return new Rule(trimmed.substring(0, open), trimmed.substring(open + 1, trimmed.length() - 1));
    }
    return new Rule(defaultToolName, trimmed);
  }

  private static boolean toolNameMatches(String actual, String expected) {
    if (expected == null || expected.isBlank() || "*".equals(expected)) return true;
    if (expected.equalsIgnoreCase(actual)) return true;
    if (actual.equalsIgnoreCase("Bash")) {
      return expected.equalsIgnoreCase("Command")
          || expected.equalsIgnoreCase("RunCommand")
          || expected.equalsIgnoreCase("run_command");
    }
    if (actual.equalsIgnoreCase("Edit")) {
      return expected.equalsIgnoreCase("Write")
          || expected.equalsIgnoreCase("Modify")
          || expected.equalsIgnoreCase("write_file")
          || expected.equalsIgnoreCase("modify_file")
          || expected.equalsIgnoreCase("edit_file");
    }
    return false;
  }

  private static boolean globMatches(String glob, String value) {
    if (glob == null || glob.isBlank()) return false;
    return Pattern.compile(globToRegex(glob.replace('\\', '/'))).matcher(value).matches();
  }

  private static String globToRegex(String glob) {
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
      char ch = glob.charAt(i);
      if (ch == '*') {
        regex.append(".*");
      } else if (ch == '?') {
        regex.append('.');
      } else if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
        regex.append('\\').append(ch);
      } else {
        regex.append(ch);
      }
    }
    return regex.append('$').toString();
  }

  private static boolean isRulePattern(String pattern) {
    String trimmed = pattern == null ? "" : pattern.trim();
    int open = trimmed.indexOf('(');
    return open > 0 && trimmed.endsWith(")");
  }

  private static boolean hasWildcard(String pattern) {
    return pattern != null && (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0);
  }

  private record Rule(String toolName, String specifier) {
  }

  private static List<String> prepend(String command, List<String> args) {
    java.util.ArrayList<String> values = new java.util.ArrayList<>();
    values.add(command);
    values.addAll(args);
    return values;
  }
}
