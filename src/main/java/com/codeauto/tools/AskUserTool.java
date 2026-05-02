package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;

public class AskUserTool implements ToolDefinition {
  @Override public String name() { return "ask_user"; }
  @Override public String description() { return "Ask the user a question and pause the current turn."; }
  @Override public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    props.set("question", JsonSchemas.stringProp("Question or message for the user"));
    return JsonSchemas.required(schema, "question");
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) {
    String question = JsonSchemas.text(input, "question", JsonSchemas.text(input, "message", ""));
    if (question.isBlank()) return ToolResult.error("question is required");
    return ToolResult.awaitUser(question);
  }
}
