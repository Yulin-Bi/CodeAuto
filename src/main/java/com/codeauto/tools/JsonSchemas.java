package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class JsonSchemas {
  static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonSchemas() {
  }

  static JsonNode objectSchema() {
    return MAPPER.createObjectNode().put("type", "object");
  }

  static String text(JsonNode input, String field, String fallback) {
    JsonNode value = input == null ? null : input.get(field);
    return value == null || value.isNull() ? fallback : value.asText(fallback);
  }
}
