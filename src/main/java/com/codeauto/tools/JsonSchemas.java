package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class JsonSchemas {
  static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonSchemas() {
  }

  /** Returns bare {"type": "object"} — kept for PlaceholderTool which has no real params. */
  static JsonNode emptySchema() {
    return MAPPER.createObjectNode().put("type", "object");
  }

  /** Start building a JSON Schema with {"type":"object"}. */
  static ObjectNode schema() {
    return MAPPER.createObjectNode().put("type", "object");
  }

  /** Create a string-type property node: {"type":"string","description":…}. */
  static ObjectNode stringProp(String description) {
    return MAPPER.createObjectNode().put("type", "string").put("description", description);
  }

  /** Create an integer-type property node: {"type":"integer","description":…}. */
  static ObjectNode integerProp(String description) {
    return MAPPER.createObjectNode().put("type", "integer").put("description", description);
  }

  /** Create a boolean-type property node: {"type":"boolean","description":…}. */
  static ObjectNode booleanProp(String description) {
    return MAPPER.createObjectNode().put("type", "boolean").put("description", description);
  }

  /** Create an array-type property node: {"type":"array","items":{"type":…},"description":…}. */
  static ObjectNode arrayProp(String itemsType, String description) {
    return MAPPER.createObjectNode()
        .put("type", "array")
        .<ObjectNode>set("items", MAPPER.createObjectNode().put("type", itemsType))
        .put("description", description);
  }

  /** Add a required field marker. */
  static ObjectNode required(ObjectNode schema, String... fields) {
    ArrayNode arr = schema.putArray("required");
    for (String f : fields) arr.add(f);
    return schema;
  }

  static String text(JsonNode input, String field, String fallback) {
    JsonNode value = input == null ? null : input.get(field);
    return value == null || value.isNull() ? fallback : value.asText(fallback);
  }

  static String textAny(JsonNode input, String fallback, String... fields) {
    if (input == null || fields == null) return fallback;
    for (String field : fields) {
      JsonNode value = input.get(field);
      if (value != null && !value.isNull()) return value.asText(fallback);
    }
    return fallback;
  }
}
