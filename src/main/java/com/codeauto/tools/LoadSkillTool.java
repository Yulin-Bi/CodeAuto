package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.skills.SessionSkills;
import com.codeauto.skills.SkillService;
import com.codeauto.todo.TodoStore;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;

public class LoadSkillTool implements ToolDefinition {
  private final Kind kind;

  public LoadSkillTool() {
    this(Kind.LOAD);
  }

  public LoadSkillTool(Kind kind) {
    this.kind = kind;
  }

  @Override
  public String name() {
    return switch (kind) {
      case LOAD -> "load_skill";
      case LIST -> "list_skills";
    };
  }

  @Override
  public String description() {
    return switch (kind) {
      case LOAD -> "Load full instructions for a skill by name. Use after list_skills to get detailed guidance.";
      case LIST -> "List available skills with descriptions. Use this to discover what skills exist and when to use each one before loading one.";
    };
  }

  @Override
  public JsonNode inputSchema() {
    return switch (kind) {
      case LOAD -> {
        ObjectNode schema = JsonSchemas.schema();
        ObjectNode props = schema.putObject("properties");
        props.set("name", JsonSchemas.stringProp("Skill name to load"));
        yield JsonSchemas.required(schema, "name");
      }
      case LIST -> JsonSchemas.schema();
    };
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    SkillService skills = new SkillService(context.cwd());
    return switch (kind) {
      case LOAD -> load(input, context, skills);
      case LIST -> list(skills, context.cwd());
    };
  }

  private ToolResult load(JsonNode input, ToolContext context, SkillService skills) throws Exception {
    String name = JsonSchemas.text(input, "name", "");
    if (name.isBlank()) return ToolResult.error("name is required");
    var summary = skills.find(name);
    if (summary.isEmpty()) return ToolResult.error("Skill not found: " + name);
    if (!context.permissions().canRead(summary.get().skillFile())) {
      return ToolResult.error("Skill path is not allowed: " + summary.get().skillFile());
    }
    String content = skills.load(name);
    SessionSkills.markLoaded(context.cwd(), name, new TodoStore(context.cwd()).activeGroupIds());
    return ToolResult.ok(content);
  }

  private static ToolResult list(SkillService skills, java.nio.file.Path cwd) {
    var entries = skills.index();
    if (entries.isEmpty()) return ToolResult.ok("(no skills available)");
    var loadedNames = new TodoStore(cwd).activeGroupIds().isEmpty()
        ? java.util.Set.<String>of()
        : SessionSkills.getLoadedNames(cwd, new TodoStore(cwd).activeGroupIds());
    StringBuilder out = new StringBuilder();
    for (var entry : entries) {
      out.append("• ").append(entry.name());
      if (loadedNames.contains(entry.name())) out.append(" [loaded]");
      out.append("\n");
      if (entry.description() != null && !entry.description().isBlank()) {
        out.append("  ").append(entry.description()).append("\n");
      }
    }
    return ToolResult.ok(out.toString().trim());
  }

  public enum Kind {
    LOAD,
    LIST
  }
}
