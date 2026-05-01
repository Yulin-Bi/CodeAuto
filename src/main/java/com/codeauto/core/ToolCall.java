package com.codeauto.core;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(String id, String toolName, JsonNode input) {
}
