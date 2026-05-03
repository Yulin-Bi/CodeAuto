package com.codeauto;

import com.codeauto.config.ConfigLoader;
import com.codeauto.config.RuntimeConfig;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigLoaderTest {
  @Test
  void writesUserSettingsThatCanBeLoaded() throws Exception {
    String previousHome = System.getProperty("codeauto.home");
    java.nio.file.Path home = Files.createTempDirectory("codeauto-config-home");
    try {
      System.setProperty("codeauto.home", home.toString());
      RuntimeConfig config = new RuntimeConfig(
          "claude-test",
          "https://example.test",
          "token",
          2048,
          2,
          900);

      ConfigLoader.writeUserSettings(config);

      RuntimeConfig loaded = new ConfigLoader().load();
      assertEquals("claude-test", loaded.model());
      assertEquals("https://example.test", loaded.baseUrl());
      assertEquals("token", loaded.authToken());
      assertEquals(2048, loaded.maxOutputTokens());
      assertEquals(2, loaded.maxRetries());
      assertEquals(900, loaded.modelTimeoutSeconds());
    } finally {
      if (previousHome == null) {
        System.clearProperty("codeauto.home");
      } else {
        System.setProperty("codeauto.home", previousHome);
      }
    }
  }
}
