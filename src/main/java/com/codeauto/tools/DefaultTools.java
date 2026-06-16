package com.codeauto.tools;

import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolRegistry;
import java.util.List;

public final class DefaultTools {
  private DefaultTools() {
  }

  public static ToolRegistry create() {
    List<ToolDefinition> tools = List.of(
        new ListFilesTool(),
        new GrepFilesTool(),
        new ReadFileTool(),
        new WriteFileTool(),
        new RunCommandTool(),
        new BackgroundTasksTool(),
        new EditFileTool(),
        new PatchFileTool(),
        new ModifyFileTool(),
        new AskUserTool(),
        new WebFetchTool(),
        new WebSearchTool(),
        new LoadSkillTool(LoadSkillTool.Kind.LIST),
        new LoadSkillTool(LoadSkillTool.Kind.LOAD),
        new MemoryTool(MemoryTool.Kind.SAVE),
        new MemoryTool(MemoryTool.Kind.LIST),
        new MemoryTool(MemoryTool.Kind.DELETE),
        new TodoTool(TodoTool.Kind.CREATE),
        new TodoTool(TodoTool.Kind.UPDATE),
        new TodoTool(TodoTool.Kind.LIST),
        new McpHelperTool(McpHelperTool.Kind.LIST_RESOURCES),
        new McpHelperTool(McpHelperTool.Kind.READ_RESOURCE),
        new McpHelperTool(McpHelperTool.Kind.LIST_PROMPTS),
        new McpHelperTool(McpHelperTool.Kind.GET_PROMPT),
        new UndoTool(UndoTool.Kind.UNDO),
        new UndoTool(UndoTool.Kind.UNDO_LIST),
        new UndoTool(UndoTool.Kind.UNDO_ALL),
        new CheckpointTool(CheckpointTool.Kind.LIST),
        new CheckpointTool(CheckpointTool.Kind.RESTORE));
    return new ToolRegistry(tools);
  }
}
