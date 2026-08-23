package com.codeauto.core;

public record ProviderUsage(int inputTokens, int outputTokens, int totalTokens, String source,
    int cacheReadInputTokens, int cacheCreationInputTokens) {
  public ProviderUsage(int inputTokens, int outputTokens, int totalTokens, String source) {
    this(inputTokens, outputTokens, totalTokens, source, 0, 0);
  }
}
