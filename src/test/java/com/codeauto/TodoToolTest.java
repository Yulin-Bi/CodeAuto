package com.codeauto;

import com.codeauto.permissions.PermissionManager;
import com.codeauto.tool.ToolContext;
import com.codeauto.tools.TodoTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoToolTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void createUsesTurnScopedGroupWhenGroupIdIsOmitted() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    Path home = Files.createTempDirectory("codeauto-todo-tool-home");
    Path project = Files.createTempDirectory("codeauto-todo-tool-project");
    try {
      System.setProperty("codeauto.home", home.toString());
      TodoTool create = new TodoTool(TodoTool.Kind.CREATE);
      TodoTool list = new TodoTool(TodoTool.Kind.LIST);
      ToolContext context = new ToolContext(project, new PermissionManager(project)).withTurnId("turn-1234");

      create.run(MAPPER.createObjectNode()
          .put("content", "Add grouped todo storage")
          .put("activeForm", "Adding grouped todo storage")
          .put("groupTitle", "Todo grouping"), context);
      create.run(MAPPER.createObjectNode()
          .put("content", "Inject recent active groups")
          .put("activeForm", "Injecting recent active groups"), context);

      String output = list.run(MAPPER.createObjectNode(), context).output();
      assertTrue(output.contains("groupId=turn-1234"));
      assertTrue(output.contains("Todo grouping"));
      assertTrue(output.contains("Add grouped todo storage"));
      assertTrue(output.contains("Inject recent active groups"));
    } finally {
      if (previousHome == null) {
        System.clearProperty("codeauto.home");
      } else {
        System.setProperty("codeauto.home", previousHome);
      }
    }
  }
}
