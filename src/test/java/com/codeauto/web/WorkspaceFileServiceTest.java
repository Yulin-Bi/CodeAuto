package com.codeauto.web;

import com.codeauto.core.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceFileServiceTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void collectsTouchedWorkspaceFilesAndRejectsTraversal() throws Exception {
    var root = Files.createTempDirectory("codeauto-web-files");
    Files.writeString(root.resolve("inside.txt"), "hello");
    Files.writeString(root.resolve("older.txt"), "old");
    var input = MAPPER.createObjectNode().put("path", "inside.txt");
    var older = MAPPER.createObjectNode().put("path", "older.txt");
    var outside = MAPPER.createObjectNode().put("path", "../outside.txt");
    var messages = List.<ChatMessage>of(
        new ChatMessage.AssistantToolCallMessage("0", "write_file", older),
        new ChatMessage.UserMessage("start the current turn"),
        new ChatMessage.AssistantToolCallMessage("1", "write_file", input),
        new ChatMessage.AssistantToolCallMessage("2", "edit_file", outside));

    var files = WorkspaceFileService.collect(root, messages, List.of());
    assertEquals(1, files.size());
    assertEquals("inside.txt", files.getFirst().path());
    assertEquals("hello", WorkspaceFileService.readText(root, "inside.txt"));
    assertThrows(IllegalArgumentException.class,
        () -> WorkspaceFileService.readText(root, "../outside.txt"));
  }
}
