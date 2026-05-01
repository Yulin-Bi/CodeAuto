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
        new LoadSkillTool(),
        new McpHelperTool(McpHelperTool.Kind.LIST_RESOURCES),
        new McpHelperTool(McpHelperTool.Kind.READ_RESOURCE),
        new McpHelperTool(McpHelperTool.Kind.LIST_PROMPTS),
        new McpHelperTool(McpHelperTool.Kind.GET_PROMPT));
    return new ToolRegistry(tools);
  }
}
