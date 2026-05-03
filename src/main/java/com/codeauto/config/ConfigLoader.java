package com.codeauto.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigLoader {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Load config with full priority chain (lowest to highest):
   * defaults → env vars → project .codeauto/settings.json → user ~/.codeauto/settings.json
   */
  public RuntimeConfig load() {
    return load(null);
  }

  /** Load config with an optional cwd for project-level config lookup. */
  public RuntimeConfig load(Path cwd) {
    RuntimeConfig config = RuntimeConfig.DEFAULTS;
    config = config.merge(fromEnvironment());
    if (cwd != null) {
      config = config.merge(fromFile(RuntimeConfig.projectDir(cwd).resolve("settings.json")));
    }
    config = config.merge(fromFile(RuntimeConfig.homeDir().resolve("settings.json")));
    return config;
  }

  /** Merge CLI overrides on top of a loaded config. */
  public static RuntimeConfig applyCliOverrides(RuntimeConfig base, CliOverrides overrides) {
    if (overrides == null) return base;
    RuntimeConfig result = base;
    if (overrides.model() != null && !overrides.model().isBlank()) {
      result = result.withModel(overrides.model());
    }
    if (overrides.maxTokens() > 0) {
      result = result.withMaxOutputTokens(overrides.maxTokens());
    }
    return result;
  }

  /** Persist user-level settings to ~/.codeauto/settings.json. */
  public static void writeUserSettings(RuntimeConfig config) throws Exception {
    Path path = RuntimeConfig.homeDir().resolve("settings.json");
    Files.createDirectories(path.getParent());
    ObjectNode json = MAPPER.createObjectNode();
    json.put("model", config.model());
    json.put("baseUrl", config.baseUrl());
    json.put("authToken", config.authToken());
    json.put("maxOutputTokens", config.maxOutputTokens());
    json.put("maxRetries", config.maxRetries());
    json.put("modelTimeoutSeconds", config.modelTimeoutSeconds());
    Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n");
  }

  public record CliOverrides(String model, int maxTokens) {
    public static final CliOverrides NONE = new CliOverrides(null, 0);
  }

  private static RuntimeConfig fromEnvironment() {
    return RuntimeConfig.DEFAULTS
        .withModel(env("CODEAUTO_MODEL"))
        .withBaseUrl(env("CODEAUTO_BASE_URL"))
        .withAuthToken(env("CODEAUTO_AUTH_TOKEN"))
        .withModelTimeoutSeconds(envInt("CODEAUTO_MODEL_TIMEOUT_SECONDS", 0));
  }

  private static String env(String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static RuntimeConfig fromFile(Path path) {
    if (!Files.exists(path)) {
      return RuntimeConfig.DEFAULTS;
    }
    try {
      JsonNode json = MAPPER.readTree(path.toFile());
      return new RuntimeConfig(
          text(json, "model", null),
          text(json, "baseUrl", null),
          text(json, "authToken", null),
          integer(json, "maxOutputTokens", 0),
          integer(json, "maxRetries", -1),
          integer(json, "modelTimeoutSeconds", 0));
    } catch (Exception error) {
      return RuntimeConfig.DEFAULTS;
    }
  }

  private static String text(JsonNode json, String field, String fallback) {
    JsonNode value = json.get(field);
    return value == null || value.isNull() ? fallback : value.asText();
  }

  private static int integer(JsonNode json, String field, int fallback) {
    JsonNode value = json.get(field);
    return value == null || !value.canConvertToInt() ? fallback : value.asInt();
  }

  private static int envInt(String name, int fallback) {
    String value = env(name);
    if (value == null) return fallback;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
