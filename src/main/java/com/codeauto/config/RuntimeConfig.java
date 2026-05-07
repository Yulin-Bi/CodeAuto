package com.codeauto.config;

import java.nio.file.Path;

public record RuntimeConfig(
    String model,
    String baseUrl,
    String authToken,
    int maxOutputTokens,
    int maxRetries,
    int modelTimeoutSeconds,
    int contextWindow,
    boolean stripThinking
) {
  public static final RuntimeConfig DEFAULTS = new RuntimeConfig("mock", "", "", 16384, 4, 600, 200_000, false);

  public RuntimeConfig withModel(String model) {
    return model == null || model.isBlank()
        ? this
        : new RuntimeConfig(model, baseUrl, authToken, maxOutputTokens, maxRetries, modelTimeoutSeconds, contextWindow, stripThinking);
  }

  public RuntimeConfig withBaseUrl(String baseUrl) {
    return baseUrl == null || baseUrl.isBlank()
        ? this
        : new RuntimeConfig(model, baseUrl, authToken, maxOutputTokens, maxRetries, modelTimeoutSeconds, contextWindow, stripThinking);
  }

  public RuntimeConfig withAuthToken(String authToken) {
    return authToken == null || authToken.isBlank()
        ? this
        : new RuntimeConfig(model, baseUrl, authToken, maxOutputTokens, maxRetries, modelTimeoutSeconds, contextWindow, stripThinking);
  }

  public RuntimeConfig withMaxOutputTokens(int maxOutputTokens) {
    return maxOutputTokens <= 0
        ? this
        : new RuntimeConfig(model, baseUrl, authToken, maxOutputTokens, maxRetries, modelTimeoutSeconds, contextWindow, stripThinking);
  }

  public RuntimeConfig withMaxRetries(int maxRetries) {
    return maxRetries < 0
        ? this
        : new RuntimeConfig(model, baseUrl, authToken, maxOutputTokens, maxRetries, modelTimeoutSeconds, contextWindow, stripThinking);
  }

  public RuntimeConfig withModelTimeoutSeconds(int modelTimeoutSeconds) {
    return modelTimeoutSeconds <= 0
        ? this
        : new RuntimeConfig(model, baseUrl, authToken, maxOutputTokens, maxRetries, modelTimeoutSeconds, contextWindow, stripThinking);
  }

  public RuntimeConfig withContextWindow(int contextWindow) {
    return contextWindow <= 0
        ? this
        : new RuntimeConfig(model, baseUrl, authToken, maxOutputTokens, maxRetries, modelTimeoutSeconds, contextWindow, stripThinking);
  }

  public RuntimeConfig withStripThinking(boolean stripThinking) {
    return stripThinking ? new RuntimeConfig(model, baseUrl, authToken, maxOutputTokens, maxRetries, modelTimeoutSeconds, contextWindow, true) : this;
  }

  /** Merge another config over this one: non-null/non-default fields in {@code overlay} win. */
  public RuntimeConfig merge(RuntimeConfig overlay) {
    if (overlay == null) return this;
    return this
        .withModel(overlay.model)
        .withBaseUrl(overlay.baseUrl)
        .withAuthToken(overlay.authToken)
        .withMaxOutputTokens(overlay.maxOutputTokens)
        .withMaxRetries(overlay.maxRetries)
        .withModelTimeoutSeconds(overlay.modelTimeoutSeconds)
        .withContextWindow(overlay.contextWindow)
        .withStripThinking(overlay.stripThinking);
  }

  public static Path homeDir() {
    String propertyOverride = System.getProperty("codeauto.home");
    if (propertyOverride != null && !propertyOverride.isBlank()) {
      return Path.of(propertyOverride);
    }
    String override = System.getenv("CODEAUTO_HOME");
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    return Path.of(System.getProperty("user.home"), ".codeauto");
  }

  /** Resolve a project-level config directory (for layered config). */
  public static Path projectDir(Path cwd) {
    return cwd.resolve(".codeauto");
  }
}
