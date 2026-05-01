package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.skills.SkillService;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;

public class LoadSkillTool implements ToolDefinition {
  @Override public String name() { return "load_skill"; }
  @Override public String description() { return "Load a local skill by name from project or managed skill config."; }
  @Override public JsonNode inputSchema() { return JsonSchemas.objectSchema(); }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String name = JsonSchemas.text(input, "name", "");
    if (name.isBlank()) return ToolResult.error("name is required");
    SkillService skills = new SkillService(context.cwd());
    var summary = skills.find(name);
    if (summary.isEmpty()) return ToolResult.error("Skill not found: " + name);
    if (!context.permissions().canRead(summary.get().skillFile())) {
      return ToolResult.error("Skill path is not allowed: " + summary.get().skillFile());
    }
    return ToolResult.ok(skills.load(name));
  }
}
