package com.codeauto.git;

import com.codeauto.config.RuntimeConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Discovers and creates Git worktrees without introducing a JGit runtime. */
public final class GitWorktreeService {
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private final Path repositoryRoot;
  private final Path managedRoot;

  public GitWorktreeService(Path repositoryRoot) {
    this(repositoryRoot, RuntimeConfig.homeDir().resolve("worktrees"));
  }

  GitWorktreeService(Path repositoryRoot, Path managedRoot) {
    this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
    this.managedRoot = managedRoot.toAbsolutePath().normalize();
  }

  public boolean available() {
    return run(repositoryRoot, "git", "rev-parse", "--show-toplevel").ok();
  }

  public List<WorktreeInfo> list() {
    ProcessResult result = run(repositoryRoot, "git", "worktree", "list", "--porcelain");
    if (!result.ok()) return List.of();
    List<WorktreeInfo> worktrees = new ArrayList<>();
    MutableInfo current = null;
    for (String raw : result.stdout().replace("\r", "").split("\n")) {
      if (raw.isBlank()) {
        if (current != null) worktrees.add(finish(current));
        current = null;
      } else if (raw.startsWith("worktree ")) {
        if (current != null) worktrees.add(finish(current));
        current = new MutableInfo(Path.of(raw.substring("worktree ".length())).toAbsolutePath().normalize());
      } else if (current != null && raw.startsWith("HEAD ")) {
        current.head = raw.substring("HEAD ".length());
      } else if (current != null && raw.startsWith("branch ")) {
        current.branch = raw.substring("branch ".length()).replaceFirst("^refs/heads/", "");
      } else if (current != null && raw.equals("detached")) {
        current.detached = true;
      } else if (current != null && raw.startsWith("locked")) {
        current.locked = true;
      } else if (current != null && raw.equals("bare")) {
        current.bare = true;
      }
    }
    if (current != null) worktrees.add(finish(current));
    return List.copyOf(worktrees);
  }

  /** Returns a bounded, parent-aware commit DAG for the repository graph UI. */
  public CommitGraph graph(int limit) {
    int bounded = Math.max(20, Math.min(limit, 500));
    ProcessResult log = run(repositoryRoot, "git", "log", "--all", "--date-order", "-n", Integer.toString(bounded),
        "--pretty=format:%H%x1f%P%x1f%s%x1f%an%x1f%aI");
    List<CommitNode> nodes = new ArrayList<>();
    List<CommitEdge> edges = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    if (log.ok()) for (String line : log.stdout().replace("\r", "").lines().toList()) {
      String[] parts = line.split("\\u001f", 5); if (parts.length < 5 || parts[0].isBlank()) continue;
      String hash=parts[0], parents=parts[1]; if (!seen.add(hash)) continue;
      nodes.add(new CommitNode(hash, parts[2], parts[3], parts[4], List.of(parents.isBlank()?new String[0]:parents.split(" "))));
      if (!parents.isBlank()) for (String parent : parents.split(" ")) edges.add(new CommitEdge(hash, parent));
    }
    Map<String,String> branches = new java.util.LinkedHashMap<>();
    ProcessResult refs = run(repositoryRoot, "git", "for-each-ref", "--format=%(refname:short)|%(objectname)", "refs/heads");
    if (refs.ok()) for (String line : refs.stdout().replace("\r", "").lines().toList()) { String[] p=line.split("\\|",2); if(p.length==2)branches.put(p[0],p[1]); }
    Map<String,String> remoteBranches = new java.util.LinkedHashMap<>();
    ProcessResult remoteRefs = run(repositoryRoot, "git", "for-each-ref", "--format=%(refname:short)|%(objectname)", "refs/remotes");
    if (remoteRefs.ok()) for (String line : remoteRefs.stdout().replace("\r", "").lines().toList()) { String[] p=line.split("\\|",2); if(p.length==2 && !p[0].endsWith("/HEAD"))remoteBranches.put(p[0],p[1]); }
    return new CommitGraph(List.copyOf(nodes), List.copyOf(edges), Map.copyOf(branches), Map.copyOf(remoteBranches));
  }

  public WorktreeInfo create(String sessionId, String baseRef) {
    return create(sessionId, baseRef, null);
  }

  public WorktreeInfo create(String sessionId, String baseRef, String requestedBranch) {
    String safeId = validateSessionId(sessionId);
    if (!available()) throw new IllegalStateException("当前工作区不是 Git 仓库，无法创建隔离工作区");
    String base = baseRef == null || baseRef.isBlank() ? "HEAD" : baseRef.trim();
    ProcessResult resolved = run(repositoryRoot, "git", "rev-parse", "--verify", base + "^{commit}");
    if (!resolved.ok() || resolved.stdout().isBlank()) {
      throw new IllegalArgumentException("无法解析 Worktree 基点：" + base);
    }
    String baseCommit = resolved.stdout().trim();
    String branch = requestedBranch == null || requestedBranch.isBlank()
        ? "codeauto/session-" + safeId : requestedBranch.trim();
    if (!branch.startsWith("codeauto/")) branch = "codeauto/" + branch;
    ProcessResult branchCheck = run(repositoryRoot, "git", "check-ref-format", "--branch", branch);
    if (!branchCheck.ok()) throw new IllegalArgumentException("Git 分支名称无效：" + branch);
    String project = repositoryRoot.toString().replaceAll("[/\\\\:]+", "-").replaceAll("^-+", "");
    Path path = managedRoot.resolve(project).resolve(safeId).normalize();
    if (!path.startsWith(managedRoot) || Files.exists(path)) {
      throw new IllegalStateException("Worktree 目录已存在：" + path);
    }
    try {
      Files.createDirectories(path.getParent());
    } catch (Exception error) {
      throw new IllegalStateException("无法创建 Worktree 父目录：" + error.getMessage(), error);
    }
    ProcessResult created = run(repositoryRoot, "git", "worktree", "add", "-b", branch,
        path.toString(), baseCommit);
    if (!created.ok()) {
      throw new IllegalStateException("创建 Git Worktree 失败：" + created.message());
    }
    return list().stream().filter(item -> item.path().equals(path)).findFirst()
        .orElse(new WorktreeInfo(path, baseCommit, branch, false, false, false, 0,
            path.equals(repositoryRoot), true, "", "", List.of()));
  }

  /** Delete only a CodeAuto-managed worktree/branch, with explicit force for data loss risks. */
  public void deleteManaged(Path requestedPath, boolean force) {
    Path path = requestedPath.toAbsolutePath().normalize();
    WorktreeInfo worktree = list().stream().filter(item -> item.path().equals(path)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Git Worktree 不存在或未注册：" + path));
    if (!worktree.managed() || !path.startsWith(managedRoot) || worktree.current()) {
      throw new IllegalArgumentException("只能删除由 CodeAuto 创建的隔离 Worktree");
    }
    if (worktree.branch() == null || !worktree.branch().startsWith("codeauto/")) {
      throw new IllegalArgumentException("只能删除 codeauto/ 命名空间中的分支");
    }
    if (worktree.changedFiles() > 0 && !force) {
      throw new IllegalStateException("Worktree 仍有 " + worktree.changedFiles() + " 个未提交修改");
    }
    if (!force) {
      ProcessResult merged = run(repositoryRoot, "git", "merge-base", "--is-ancestor",
          worktree.branch(), "HEAD");
      if (!merged.ok()) throw new IllegalStateException("分支尚未合并到当前分支");
    }
    List<String> remove = new ArrayList<>(List.of("git", "worktree", "remove"));
    if (force) remove.add("--force");
    remove.add(path.toString());
    ProcessResult removed = run(repositoryRoot, remove.toArray(String[]::new));
    if (!removed.ok()) throw new IllegalStateException("删除 Worktree 失败：" + removed.message());
    ProcessResult branchDeleted = run(repositoryRoot, "git", "branch", force ? "-D" : "-d",
        worktree.branch());
    if (!branchDeleted.ok()) {
      throw new IllegalStateException("Worktree 已移除，但删除 Git 分支失败：" + branchDeleted.message());
    }
  }

  public void deleteManagedBranch(String branch, boolean force) {
    if (branch == null || !branch.startsWith("codeauto/")) {
      throw new IllegalArgumentException("只能删除 codeauto/ 命名空间中的分支");
    }
    if (!force && !run(repositoryRoot, "git", "merge-base", "--is-ancestor", branch, "HEAD").ok()) {
      throw new IllegalStateException("分支尚未合并到当前分支");
    }
    ProcessResult deleted = run(repositoryRoot, "git", "branch", force ? "-D" : "-d", branch);
    if (!deleted.ok()) throw new IllegalStateException("删除 Git 分支失败：" + deleted.message());
  }

  /** Only rolls back a worktree created by this service before an Agent can use it. */
  public void rollbackCreated(WorktreeInfo worktree) {
    if (worktree == null || !worktree.managed() || !worktree.path().startsWith(managedRoot)) return;
    run(repositoryRoot, "git", "worktree", "remove", "--force", worktree.path().toString());
    if (worktree.branch() != null && worktree.branch().startsWith("codeauto/session-")) {
      run(repositoryRoot, "git", "branch", "-D", worktree.branch());
    }
  }

  public boolean isRegistered(Path path) {
    if (path == null) return false;
    Path normalized = path.toAbsolutePath().normalize();
    return list().stream().anyMatch(item -> item.path().equals(normalized));
  }

  /** Return the actionable Git state for one registered worktree. */
  public GitStatus status(Path requestedPath) {
    Path path = requireRegisteredWorktree(requestedPath);
    String branch = output(path, "git", "symbolic-ref", "--quiet", "--short", "HEAD");
    boolean detached = branch.isBlank();
    String upstream = output(path, "git", "rev-parse", "--abbrev-ref", "--symbolic-full-name",
        "@{upstream}");
    int ahead = 0;
    int behind = 0;
    if (!upstream.isBlank()) {
      ProcessResult counts = run(path, "git", "rev-list", "--left-right", "--count",
          "HEAD...@{upstream}");
      if (counts.ok()) {
        String[] parts = counts.stdout().trim().split("\\s+");
        if (parts.length >= 2) {
          ahead = parseCount(parts[0]);
          behind = parseCount(parts[1]);
        }
      }
    }
    List<String> remotes = output(path, "git", "remote").lines()
        .map(String::trim).filter(value -> !value.isBlank()).distinct().sorted().toList();
    String remote = branch.isBlank() ? "" : output(path, "git", "config", "--get",
        "branch." + branch + ".remote");
    if (".".equals(remote)) remote = "";

    ProcessResult rawStatus = run(path, "git", "status", "--porcelain=v1", "-z",
        "--untracked-files=all");
    if (!rawStatus.ok()) throw new IllegalStateException("读取 Git 状态失败：" + rawStatus.message());
    List<ChangedFile> files = parseStatus(rawStatus.stdout());
    return new GitStatus(path, branch, detached, upstream, remote, ahead, behind, remotes, files);
  }

  public synchronized GitStatus stage(Path requestedPath, List<String> requestedFiles,
      boolean all) {
    Path path = requireRegisteredWorktree(requestedPath);
    if (all) {
      requireOk(run(path, "git", "add", "-A"), "暂存全部文件失败");
    } else {
      for (String file : validateChangedFiles(path, requestedFiles)) {
        requireOk(run(path, "git", "add", "--", file), "暂存文件失败");
      }
    }
    return status(path);
  }

  public synchronized GitStatus unstage(Path requestedPath, List<String> requestedFiles,
      boolean all) {
    Path path = requireRegisteredWorktree(requestedPath);
    List<String> files = all
        ? status(path).files().stream().filter(ChangedFile::staged).map(ChangedFile::path).toList()
        : validateChangedFiles(path, requestedFiles);
    if (files.isEmpty()) throw new IllegalArgumentException("没有可取消暂存的文件");
    for (String file : files) {
      requireOk(run(path, "git", "reset", "--quiet", "HEAD", "--", file), "取消暂存失败");
    }
    return status(path);
  }

  public String diff(Path requestedPath, String requestedFile, boolean staged) {
    Path path = requireRegisteredWorktree(requestedPath);
    String file = validateRelativePath(requestedFile);
    GitStatus current = status(path);
    ChangedFile changed = current.files().stream().filter(item -> item.path().equals(file))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("文件不在当前 Git 变更中：" + file));
    ProcessResult result;
    if (changed.untracked() && !staged) {
      result = run(path, "git", "diff", "--no-index", "--no-ext-diff", "--", "NUL", file);
      if (result.exitCode() != 0 && result.exitCode() != 1) {
        throw new IllegalStateException("读取未跟踪文件 Diff 失败：" + result.message());
      }
    } else {
      List<String> command = new ArrayList<>(List.of("git", "diff", "--no-ext-diff",
          "--unified=3"));
      if (staged) command.add("--cached");
      command.add("--");
      command.add(file);
      result = run(path, command.toArray(String[]::new));
      if (!result.ok()) throw new IllegalStateException("读取 Diff 失败：" + result.message());
    }
    String diff = result.stdout();
    if (diff.length() > 200_000) diff = diff.substring(0, 200_000) + "\n…[Diff 已截断]";
    return diff.isBlank() ? "该区域没有可显示的文本 Diff（可能是二进制文件）。" : diff;
  }

  public synchronized GitOperationResult commit(Path requestedPath, String requestedMessage) {
    Path path = requireRegisteredWorktree(requestedPath);
    String message = requestedMessage == null ? "" : requestedMessage.strip();
    if (message.isBlank()) throw new IllegalArgumentException("请输入提交信息");
    if (message.length() > 2_000) throw new IllegalArgumentException("提交信息不能超过 2000 个字符");
    ProcessResult staged = run(path, "git", "diff", "--cached", "--quiet", "--exit-code");
    if (staged.exitCode() == 0) throw new IllegalStateException("暂存区为空，请先暂存需要提交的文件");
    if (staged.exitCode() != 1) throw new IllegalStateException("无法检查暂存区：" + staged.message());
    ProcessResult committed = run(path, "git", "commit", "-m", message);
    requireOk(committed, "提交失败");
    return new GitOperationResult(committed.message(), status(path));
  }

  public synchronized GitOperationResult push(Path requestedPath, String requestedRemote) {
    Path path = requireRegisteredWorktree(requestedPath);
    GitStatus current = status(path);
    if (current.detached() || current.branch().isBlank()) {
      throw new IllegalStateException("Detached HEAD 无法直接推送，请先切换到分支");
    }
    String remote = requestedRemote == null ? "" : requestedRemote.strip();
    ProcessResult pushed;
    if (!current.upstream().isBlank() && remote.isBlank()) {
      pushed = run(Duration.ofMinutes(2), path, "git", "push");
    } else {
      if (remote.isBlank()) {
        remote = current.remotes().contains("origin") ? "origin"
            : current.remotes().size() == 1 ? current.remotes().getFirst() : "";
      }
      if (remote.isBlank()) throw new IllegalStateException("仓库尚未配置可用远端");
      if (!current.remotes().contains(remote)) throw new IllegalArgumentException("未知 Git 远端：" + remote);
      pushed = run(Duration.ofMinutes(2), path, "git", "push", "--set-upstream", remote,
          current.branch());
    }
    requireOk(pushed, "推送失败，请检查远端地址和系统 Git 凭据");
    return new GitOperationResult(pushed.message(), status(path));
  }

  private WorktreeInfo finish(MutableInfo value) {
    ProcessResult status = value.bare ? new ProcessResult(0, "", "")
        : run(value.path, "git", "status", "--porcelain");
    int changed = status.ok() && !status.stdout().isBlank()
        ? (int) status.stdout().lines().filter(line -> !line.isBlank()).count() : 0;
    ProcessResult commit = value.bare ? new ProcessResult(0, "", "")
        : run(value.path, "git", "log", "-12", "--format=%H%x1f%s%x1f%an");
    List<CommitInfo> commits = new ArrayList<>();
    if (commit.ok()) {
      for (String line : commit.stdout().lines().toList()) {
        String[] parts = line.split("\\u001f", 3);
        if (parts.length >= 2) commits.add(new CommitInfo(parts[0], parts[1], parts.length > 2 ? parts[2] : ""));
      }
    }
    return new WorktreeInfo(value.path, value.head, value.branch, value.detached, value.locked,
        value.bare, changed, value.path.equals(repositoryRoot), value.path.startsWith(managedRoot),
        commits.isEmpty() ? "" : commits.getFirst().subject(),
        commits.isEmpty() ? "" : commits.getFirst().author(), List.copyOf(commits));
  }

  private Path requireRegisteredWorktree(Path requestedPath) {
    if (requestedPath == null) throw new IllegalArgumentException("Worktree 路径不能为空");
    Path path = requestedPath.toAbsolutePath().normalize();
    ProcessResult registered = run(repositoryRoot, "git", "worktree", "list", "--porcelain");
    boolean found = registered.ok() && registered.stdout().replace("\r", "").lines()
        .filter(line -> line.startsWith("worktree "))
        .map(line -> Path.of(line.substring("worktree ".length())).toAbsolutePath().normalize())
        .anyMatch(path::equals);
    if (!found || !Files.isDirectory(path)) {
      throw new IllegalArgumentException("目录不是已注册的 Git Worktree");
    }
    if ("true".equals(output(path, "git", "rev-parse", "--is-bare-repository"))) {
      throw new IllegalArgumentException("裸仓库不支持此操作");
    }
    return path;
  }

  private List<String> validateChangedFiles(Path path, List<String> requestedFiles) {
    if (requestedFiles == null || requestedFiles.isEmpty()) {
      throw new IllegalArgumentException("请选择至少一个文件");
    }
    if (requestedFiles.size() > 500) throw new IllegalArgumentException("一次最多处理 500 个文件");
    Set<String> changed = new LinkedHashSet<>();
    for (ChangedFile file : status(path).files()) changed.add(file.path());
    List<String> files = new ArrayList<>();
    for (String requested : requestedFiles) {
      String file = validateRelativePath(requested);
      if (!changed.contains(file)) throw new IllegalArgumentException("文件不在当前 Git 变更中：" + file);
      if (!files.contains(file)) files.add(file);
    }
    return List.copyOf(files);
  }

  private static String validateRelativePath(String requested) {
    if (requested == null || requested.isBlank() || requested.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Git 文件路径不能为空");
    }
    String value = requested.replace('\\', '/');
    Path path = Path.of(value).normalize();
    if (path.isAbsolute() || value.startsWith("../") || "..".equals(value)
        || path.startsWith("..")) throw new IllegalArgumentException("Git 文件路径越界");
    return value;
  }

  private static List<ChangedFile> parseStatus(String raw) {
    if (raw == null || raw.isEmpty()) return List.of();
    String[] entries = raw.split("\u0000", -1);
    List<ChangedFile> files = new ArrayList<>();
    for (int index = 0; index < entries.length; index++) {
      String entry = entries[index];
      if (entry.length() < 4) continue;
      char x = entry.charAt(0);
      char y = entry.charAt(1);
      String file = entry.substring(3).replace('\\', '/');
      boolean renamed = x == 'R' || x == 'C' || y == 'R' || y == 'C';
      if (renamed && index + 1 < entries.length && !entries[index + 1].isEmpty()) index++;
      boolean untracked = x == '?' && y == '?';
      boolean staged = !untracked && x != ' ';
      boolean unstaged = untracked || y != ' ';
      boolean conflict = x == 'U' || y == 'U' || (x == 'A' && y == 'A')
          || (x == 'D' && y == 'D');
      files.add(new ChangedFile(file, String.valueOf(x), String.valueOf(y), staged, unstaged,
          untracked, conflict));
    }
    return List.copyOf(files);
  }

  private static int parseCount(String value) {
    try { return Integer.parseInt(value); }
    catch (NumberFormatException ignored) { return 0; }
  }

  private static String output(Path path, String... command) {
    ProcessResult result = run(path, command);
    return result.ok() ? result.stdout().trim() : "";
  }

  private static void requireOk(ProcessResult result, String action) {
    if (!result.ok()) throw new IllegalStateException(action + "：" + result.message());
  }

  private static String validateSessionId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
      throw new IllegalArgumentException("Invalid session id");
    }
    return id;
  }

  private static ProcessResult run(Path cwd, String... command) {
    return run(TIMEOUT, cwd, command);
  }

  private static ProcessResult run(Duration timeout, Path cwd, String... command) {
    try {
      ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
      builder.environment().put("GIT_TERMINAL_PROMPT", "0");
      builder.environment().put("GIT_LITERAL_PATHSPECS", "1");
      Process process = builder.start();
      CompletableFuture<String> stdout = read(process.getInputStream());
      CompletableFuture<String> stderr = read(process.getErrorStream());
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return new ProcessResult(-1, "", "Git command timed out");
      }
      return new ProcessResult(process.exitValue(), stdout.join(), stderr.join().trim());
    } catch (Exception error) {
      return new ProcessResult(-1, "", error.getMessage() == null ? error.toString() : error.getMessage());
    }
  }

  private static CompletableFuture<String> read(java.io.InputStream stream) {
    return CompletableFuture.supplyAsync(() -> {
      try { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
      catch (Exception ignored) { return ""; }
    });
  }

  public record WorktreeInfo(Path path, String head, String branch, boolean detached, boolean locked,
      boolean bare, int changedFiles, boolean current, boolean managed, String commitSubject,
      String commitAuthor, List<CommitInfo> recentCommits) {
  }

  public record CommitInfo(String hash, String subject, String author) {
  }

  public record CommitGraph(List<CommitNode> nodes, List<CommitEdge> edges, Map<String,String> branches,
      Map<String,String> remoteBranches) {
  }

  public record CommitNode(String hash, String subject, String author, String timestamp, List<String> parents) {
  }

  public record CommitEdge(String child, String parent) {
  }

  public record ChangedFile(String path, String indexStatus, String worktreeStatus, boolean staged,
      boolean unstaged, boolean untracked, boolean conflict) {
  }

  public record GitStatus(Path path, String branch, boolean detached, String upstream,
      String remote, int ahead, int behind, List<String> remotes, List<ChangedFile> files) {
  }

  public record GitOperationResult(String message, GitStatus status) {
  }

  private static final class MutableInfo {
    final Path path;
    String head = "";
    String branch;
    boolean detached;
    boolean locked;
    boolean bare;
    MutableInfo(Path path) { this.path = path; }
  }

  private record ProcessResult(int exitCode, String stdout, String stderr) {
    boolean ok() { return exitCode == 0; }
    String message() { return stderr == null || stderr.isBlank() ? stdout : stderr; }
  }
}
