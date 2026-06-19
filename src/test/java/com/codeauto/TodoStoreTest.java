package com.codeauto;

import com.codeauto.todo.TodoStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoStoreTest {
  @Test
  void promptContextFocusesOnRecentUnfinishedGroups() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    Path home = Files.createTempDirectory("codeauto-todo-home");
    Path project = Files.createTempDirectory("codeauto-todo-project");
    try {
      System.setProperty("codeauto.home", home.toString());
      TodoStore store = new TodoStore(project);

      var done = store.add("Old completed task", "Finishing old completed task", "legacy-fix", "Legacy fix", "turn-old");
      store.update(done.id(), "completed", null);

      var current = store.add("Fix reflection injection", "Fixing reflection injection",
          "reflection-fix", "Reflection follow-up", "turn-a");
      store.add("Reduce bullet prompt volume", "Reducing bullet prompt volume",
          "reflection-fix", "Reflection follow-up", "turn-a");
      store.update(current.id(), "in_progress", null);

      store.add("Tighten todo sidebar", "Tightening todo sidebar",
          "tui-sidebar", "TUI polish", "turn-b");

      String prompt = store.promptContext();
      assertTrue(prompt.contains("groupId=tui-sidebar"));
      assertTrue(prompt.contains("groupId=reflection-fix"));
      assertTrue(prompt.contains("title=TUI polish"));
      assertTrue(prompt.contains("current: Fixing reflection injection"));
      assertTrue(prompt.contains("pending: Reduce bullet prompt volume"));
      assertFalse(prompt.contains("Legacy fix"));
      assertTrue(prompt.contains("Reuse groupId"));
    } finally {
      restoreProperty("codeauto.home", previousHome);
    }
  }

  @Test
  void activeContextTextsPreferRecentActiveGroups() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    Path home = Files.createTempDirectory("codeauto-todo-active-home");
    Path project = Files.createTempDirectory("codeauto-todo-active-project");
    try {
      System.setProperty("codeauto.home", home.toString());
      TodoStore store = new TodoStore(project);

      var old = store.add("Archive old checklist", "Archiving old checklist",
          "old-task", "Old task", "turn-a");
      store.update(old.id(), "completed", null);

      store.add("Improve todo injection", "Improving todo injection",
          "todo-injection", "Todo injection", "turn-b");

      var texts = store.activeContextTexts();
      assertTrue(texts.contains("Todo injection"));
      assertTrue(texts.contains("Improve todo injection"));
      assertFalse(texts.contains("Old task"));
    } finally {
      restoreProperty("codeauto.home", previousHome);
    }
  }

  @Test
  void groupsFallBackForLegacyEntriesWithoutGroupMetadata() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    Path home = Files.createTempDirectory("codeauto-todo-legacy-home");
    Path project = Files.createTempDirectory("codeauto-todo-legacy-project");
    try {
      System.setProperty("codeauto.home", home.toString());
      TodoStore store = new TodoStore(project);
      String projectName = project.toString().replaceAll("[/\\\\:]+", "-").replaceAll("^-+", "");
      Path todoFile = home.resolve("todos").resolve(projectName + ".json");
      Files.createDirectories(todoFile.getParent());
      Files.writeString(todoFile, """
          [
            {
              "id": "legacy01",
              "content": "Legacy todo",
              "status": "pending",
              "activeForm": "Legacy todo",
              "createdAt": "2026-06-17T00:00:00Z",
              "updatedAt": "2026-06-17T00:00:00Z"
            }
          ]
          """);

      var groups = store.groups();
      assertEquals(1, groups.size());
      assertEquals("legacy-legacy01", groups.getFirst().id());
      assertEquals("Legacy todo", groups.getFirst().title());
    } finally {
      restoreProperty("codeauto.home", previousHome);
    }
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
