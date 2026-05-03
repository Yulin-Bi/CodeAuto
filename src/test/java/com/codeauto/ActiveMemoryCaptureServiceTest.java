package com.codeauto;

import com.codeauto.core.ChatMessage;
import com.codeauto.memory.ActiveMemoryCaptureService;
import com.codeauto.memory.MemoryManager;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveMemoryCaptureServiceTest {
  @Test
  void capturesExplicitMemoryCandidateWithoutSaving() throws Exception {
    java.nio.file.Path root = Files.createTempDirectory("codeauto-active-memory");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-active-memory-project");
    MemoryManager manager = new MemoryManager(root);
    ActiveMemoryCaptureService service = new ActiveMemoryCaptureService(manager);

    var candidates = service.captureCandidates(project, List.of(
        new ChatMessage.UserMessage("remember: prefer concise Chinese answers."),
        new ChatMessage.AssistantMessage("OK.")), 0);

    assertEquals(1, candidates.size());
    assertTrue(candidates.getFirst().content().contains("concise Chinese"));
    assertTrue(manager.list().isEmpty());
  }

  @Test
  void savesCandidateToMemoryStoreWhenUserChoosesStore() throws Exception {
    java.nio.file.Path root = Files.createTempDirectory("codeauto-active-memory-store");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-active-memory-project");
    MemoryManager manager = new MemoryManager(root);
    ActiveMemoryCaptureService service = new ActiveMemoryCaptureService(manager);
    var candidate = service.captureCandidates(project,
        List.of(new ChatMessage.UserMessage("remember: prefer concise Chinese answers.")), 0).getFirst();

    service.saveToMemory(project, candidate);

    assertEquals(1, manager.list().size());
    assertTrue(manager.list().getFirst().content().contains("concise Chinese"));
  }

  @Test
  void savesCandidateToProjectClaudeWhenUserChoosesProject() throws Exception {
    java.nio.file.Path root = Files.createTempDirectory("codeauto-active-memory-project-claude-root");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-active-memory-project-claude");
    ActiveMemoryCaptureService service = new ActiveMemoryCaptureService(new MemoryManager(root));
    var candidate = service.captureCandidates(project,
        List.of(new ChatMessage.UserMessage("project test command is mvn test.")), 0).getFirst();

    java.nio.file.Path path = service.saveToProjectClaude(project, candidate);

    assertEquals(project.resolve("CLAUDE.md"), path);
    assertTrue(Files.readString(path).contains("project test command is mvn test"));
  }

  @Test
  void skipsDuplicateAndOrdinaryChat() throws Exception {
    java.nio.file.Path root = Files.createTempDirectory("codeauto-active-memory-duplicates");
    java.nio.file.Path project = Files.createTempDirectory("codeauto-project");
    MemoryManager manager = new MemoryManager(root);
    ActiveMemoryCaptureService service = new ActiveMemoryCaptureService(manager);

    var candidate = service.captureCandidates(project,
        List.of(new ChatMessage.UserMessage("remember: prefer concise Chinese answers.")), 0).getFirst();
    service.saveToMemory(project, candidate);

    assertTrue(service.captureCandidates(project,
        List.of(new ChatMessage.UserMessage("remember: prefer concise Chinese answers.")), 0).isEmpty());
    assertTrue(service.captureCandidates(project,
        List.of(new ChatMessage.UserMessage("Please review README.")), 0).isEmpty());
  }
}
