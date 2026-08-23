package com.codeauto;

import com.codeauto.core.ChatMessage;
import com.codeauto.session.SessionStore;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {
  @Test
  void savesAndLoadsJsonlMessages() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);

    store.save("abc123", List.of(
        new ChatMessage.SystemMessage("sys"),
        new ChatMessage.UserMessage("hello"),
        new ChatMessage.AssistantMessage("world")), 1);

    List<ChatMessage> loaded = store.load("abc123");

    assertEquals(2, loaded.size());
    assertEquals("user", loaded.getFirst().role());
    assertEquals("assistant", loaded.get(1).role());
  }

  @Test
  void renameEventDoesNotBreakLoadingMessages() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-rename-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-rename-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);

    store.save("abc123", List.of(new ChatMessage.UserMessage("hello")), 0);
    store.rename("abc123", "My Session");

    List<ChatMessage> loaded = store.load("abc123");

    assertEquals(1, loaded.size());
    assertEquals("hello", ((ChatMessage.UserMessage) loaded.getFirst()).content());
  }

  @Test
  void listsSessionSummariesWithRenameTitle() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-list-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-list-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);

    store.save("abc123", List.of(new ChatMessage.UserMessage("first prompt")), 0);
    store.rename("abc123", "Readable Name");

    List<SessionStore.SessionSummary> summaries = store.list();

    assertEquals(1, summaries.size());
    assertEquals("abc123", summaries.getFirst().id());
    assertEquals("Readable Name", summaries.getFirst().title());
  }

  @Test
  void loadStartsAfterLatestCompactBoundary() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-compact-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-compact-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);

    store.save("abc123", List.of(new ChatMessage.UserMessage("old")), 0);
    store.appendCompactBoundary("abc123", new ChatMessage.ContextSummaryMessage("summary", 1, 1L), "manual", 100, 20);
    store.save("abc123", List.of(new ChatMessage.UserMessage("new")), 0);

    List<ChatMessage> loaded = store.load("abc123");

    assertEquals(2, loaded.size());
    assertEquals("context_summary", loaded.getFirst().role());
    assertEquals("new", ((ChatMessage.UserMessage) loaded.get(1)).content());
  }

  @Test
  void loadTranscriptRebuildsFullVisibleEventHistory() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-transcript-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-transcript-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);

    store.save("abc123", List.of(
        new ChatMessage.UserMessage("old"),
        new ChatMessage.AssistantMessage("answer")), 0);
    store.appendCompactBoundary("abc123", new ChatMessage.ContextSummaryMessage("summary", 1, 1L), "manual", 100, 20);
    store.save("abc123", List.of(new ChatMessage.UserMessage("new")), 0);

    List<SessionStore.TranscriptEntry> transcript = store.loadTranscript("abc123");

    assertEquals("user", transcript.getFirst().kind());
    assertEquals("old", transcript.getFirst().body());
    assertTrue(transcript.stream().anyMatch(entry -> entry.body().contains("Context compacted")));
    assertTrue(transcript.stream().anyMatch(entry -> entry.body().contains("Context summary")));
    assertEquals("new", transcript.getLast().body());
  }

  @Test
  void cleanupExpiredSessionsRemovesOldJsonlFiles() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-cleanup-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-cleanup-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);
    store.save("old", List.of(new ChatMessage.UserMessage("old")), 0);
    store.save("new", List.of(new ChatMessage.UserMessage("new")), 0);
    java.nio.file.Path projectDir = home.resolve("projects")
        .resolve(temp.toAbsolutePath().normalize().toString().replaceAll("[/\\\\:]+", "-").replaceAll("^-+", ""));
    Files.setLastModifiedTime(projectDir.resolve("old.jsonl"), FileTime.from(Instant.now().minus(Duration.ofDays(40))));

    int removed = store.cleanupExpiredSessions(Duration.ofDays(30));

    assertEquals(1, removed);
    assertTrue(!Files.exists(projectDir.resolve("old.jsonl")));
    assertTrue(Files.exists(projectDir.resolve("new.jsonl")));
  }

  @Test
  void multipleCompactBoundariesPreserveSummaryChain() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-multi-compact-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-multi-compact-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);

    store.save("abc123", List.of(new ChatMessage.UserMessage("msg1")), 0);
    store.appendCompactBoundary("abc123", new ChatMessage.ContextSummaryMessage("first compact", 1, 1L), "auto", 100, 30);
    store.save("abc123", List.of(new ChatMessage.UserMessage("msg2")), 0);
    store.appendCompactBoundary("abc123", new ChatMessage.ContextSummaryMessage("second compact", 1, 2L), "manual", 80, 20);
    store.save("abc123", List.of(new ChatMessage.UserMessage("msg3")), 0);

    List<ChatMessage> loaded = store.load("abc123");

    assertEquals(3, loaded.size());
    assertEquals("context_summary", loaded.getFirst().role());
    assertTrue(((ChatMessage.ContextSummaryMessage) loaded.getFirst()).content().contains("first compact"));
    assertEquals("context_summary", loaded.get(1).role());
    assertTrue(((ChatMessage.ContextSummaryMessage) loaded.get(1)).content().contains("second compact"));
    assertEquals("msg3", ((ChatMessage.UserMessage) loaded.get(2)).content());
  }

  @Test
  void forkMetadataAndMessagesSurviveReloadWithoutDuplicateSystemPrompt() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-fork-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-fork-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);

    List<ChatMessage> parentMessages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("parent prompt"),
        new ChatMessage.AssistantMessage("parent answer"));
    store.save("parent01", parentMessages, 1);

    store.markFork("child01", "parent01", parentMessages.size(), "Readable branch");
    store.save("child01", parentMessages, 1);

    List<ChatMessage> child = store.load("child01");
    assertEquals(2, child.size());
    assertEquals("parent prompt", ((ChatMessage.UserMessage) child.getFirst()).content());

    SessionStore.SessionSummary summary = store.list().stream()
        .filter(item -> item.id().equals("child01"))
        .findFirst().orElseThrow();
    assertEquals("Readable branch", summary.title());
    assertEquals("parent01", summary.parentSessionId());
    assertEquals(parentMessages.size(), summary.forkBoundary());
  }

  @Test
  void multipleSiblingForksArePersistedIndependently() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-sibling-fork-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-sibling-fork-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);
    List<ChatMessage> messages = List.of(
        new ChatMessage.SystemMessage("system"),
        new ChatMessage.UserMessage("hello"),
        new ChatMessage.AssistantMessage("world"));

    store.save("parent02", messages, 1);
    store.createFork("child02a", "parent02", messages.size(), "Branch A", messages, 1);
    store.createFork("child02b", "parent02", messages.size(), "Branch B", messages, 1);

    Map<String, SessionStore.SessionSummary> summaries = store.list().stream()
        .collect(java.util.stream.Collectors.toMap(SessionStore.SessionSummary::id, item -> item));
    assertEquals("parent02", summaries.get("child02a").parentSessionId());
    assertEquals("parent02", summaries.get("child02b").parentSessionId());
    assertEquals(2, store.load("child02a").size());
    assertEquals(2, store.load("child02b").size());
  }

  @Test
  void oldSessionEventsWithoutForkFieldsRemainReadable() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-legacy-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-legacy-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);

    store.save("legacy01", List.of(new ChatMessage.UserMessage("legacy")), 0);
    SessionStore.SessionSummary summary = store.list().getFirst();
    assertEquals("legacy01", summary.id());
    assertEquals("legacy", summary.title());
    assertEquals(null, summary.parentSessionId());
    assertEquals(null, summary.forkBoundary());
  }

  @Test
  void persistsForkWorktreeBindingWithConversationMetadata() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-worktree-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-worktree-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);

    store.createFork("child01", "parent01", 2, "isolated branch",
        List.of(new ChatMessage.SystemMessage("system"), new ChatMessage.UserMessage("hello")),
        1, "D:\\worktrees\\child01", "codeauto/session-child01", "abc123");

    SessionStore.SessionSummary summary = store.list().getFirst();
    assertEquals("parent01", summary.parentSessionId());
    assertEquals("D:\\worktrees\\child01", summary.worktreePath());
    assertEquals("codeauto/session-child01", summary.gitBranch());
    assertEquals("abc123", summary.baseCommit());
  }

  @Test
  void compactBoundaryWithoutSubsequentMessagesLoadsOnlySummary() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-compact-only-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-compact-only-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);

    store.save("abc123", List.of(new ChatMessage.UserMessage("old")), 0);
    store.appendCompactBoundary("abc123", new ChatMessage.ContextSummaryMessage("summary", 1, 1L), "auto", 100, 20);

    List<ChatMessage> loaded = store.load("abc123");

    assertEquals(1, loaded.size());
    assertEquals("context_summary", loaded.getFirst().role());
  }

  @Test
  void listsAllProjectsWithSessionCountsAndLatestUpdate() throws Exception {
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-projects-test");
    System.setProperty("codeauto.home", home.toString());
    java.nio.file.Path projectA = Files.createTempDirectory("codeauto-project-a");
    java.nio.file.Path projectB = Files.createTempDirectory("codeauto-project-b");
    new SessionStore(projectA).save("a1", List.of(new ChatMessage.UserMessage("a")), 0);
    new SessionStore(projectA).save("a2", List.of(new ChatMessage.UserMessage("a2")), 0);
    new SessionStore(projectB).save("b1", List.of(new ChatMessage.UserMessage("b")), 0);

    List<SessionStore.ProjectMeta> projects = SessionStore.listAllProjects();

    assertEquals(2, projects.size());
    assertTrue(projects.stream().anyMatch(project ->
        project.cwd().equals(projectA.toAbsolutePath().normalize().toString())
            && project.sessionCount() == 2));
    assertTrue(projects.stream().anyMatch(project ->
        project.cwd().equals(projectB.toAbsolutePath().normalize().toString())
            && project.sessionCount() == 1));
    assertTrue(projects.getFirst().latestUpdatedAt() >= projects.get(1).latestUpdatedAt());
  }

  @Test
  void concurrentAppendsDoNotLoseEvents() throws Exception {
    java.nio.file.Path temp = Files.createTempDirectory("codeauto-session-concurrent-test");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-home-concurrent-test");
    System.setProperty("codeauto.home", home.toString());
    SessionStore store = new SessionStore(temp);
    int writers = 8;
    int messagesPerWriter = 20;
    var pool = Executors.newFixedThreadPool(writers);
    var barrier = new CyclicBarrier(writers);
    List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

    for (int writer = 0; writer < writers; writer++) {
      final int writerId = writer;
      futures.add(pool.submit(() -> {
        barrier.await();
        for (int i = 0; i < messagesPerWriter; i++) {
          store.save("abc123", List.of(new ChatMessage.UserMessage("writer-" + writerId + "-msg-" + i)), 0);
        }
        return null;
      }));
    }
    for (var future : futures) {
      future.get();
    }
    pool.shutdown();

    List<ChatMessage> loaded = store.load("abc123");
    assertEquals(writers * messagesPerWriter, loaded.size());
    Set<String> contents = loaded.stream()
        .map(message -> ((ChatMessage.UserMessage) message).content())
        .collect(java.util.stream.Collectors.toSet());
    assertEquals(writers * messagesPerWriter, contents.size());
  }
}
