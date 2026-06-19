package com.codeauto;

import com.codeauto.todo.TodoStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoStoreTest {
  @Test
  void summaryIncludesCurrentAndNextTodos() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    Path home = Files.createTempDirectory("codeauto-todo-home");
    Path project = Files.createTempDirectory("codeauto-todo-project");
    try {
      System.setProperty("codeauto.home", home.toString());
      TodoStore store = new TodoStore(project);
      var current = store.add("Fix reflection injection", "Fixing reflection injection");
      store.add("Reduce bullet prompt volume", "Reducing bullet prompt volume");
      store.add("Improve todo summary", "Improving todo summary");
      store.update(current.id(), "in_progress", null);

      String summary = store.summary();
      assertTrue(summary.contains("Current: Fixing reflection injection."));
      assertTrue(summary.contains("Next: Reduce bullet prompt volume | Improve todo summary."));
      assertTrue(summary.contains("Call todo_list"));
    } finally {
      if (previousHome == null) {
        System.clearProperty("codeauto.home");
      } else {
        System.setProperty("codeauto.home", previousHome);
      }
    }
  }

  @Test
  void summaryIsEmptyWhenNoActiveTodosRemain() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    Path home = Files.createTempDirectory("codeauto-todo-empty-home");
    Path project = Files.createTempDirectory("codeauto-todo-empty-project");
    try {
      System.setProperty("codeauto.home", home.toString());
      TodoStore store = new TodoStore(project);
      var done = store.add("Completed item", "Completing item");
      store.update(done.id(), "completed", null);

      assertTrue(store.summary().isEmpty());
    } finally {
      if (previousHome == null) {
        System.clearProperty("codeauto.home");
      } else {
        System.setProperty("codeauto.home", previousHome);
      }
    }
  }
}
