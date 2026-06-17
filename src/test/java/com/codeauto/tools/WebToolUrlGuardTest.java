package com.codeauto.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebToolUrlGuardTest {
  @Test
  void rejectsLoopbackAndPrivateHosts() {
    assertTrue(WebToolUrlGuard.validatePublicHttpUrl("http://127.0.0.1/test").contains("private/internal"));
    assertTrue(WebToolUrlGuard.validatePublicHttpUrl("http://localhost/test").contains("private/internal"));
    assertTrue(WebToolUrlGuard.validatePublicHttpUrl("http://[fd00::1]/test").contains("private/internal"));
  }

  @Test
  void rejectsNonHttpSchemesAndEmbeddedCredentials() {
    assertTrue(WebToolUrlGuard.validatePublicHttpUrl("file:///tmp/test").contains("Only http/https"));
    assertTrue(WebToolUrlGuard.validatePublicHttpUrl("https://user:pass@example.com/").contains("embedded credentials"));
  }

  @Test
  void acceptsResolvablePublicHttpAddress() {
    assertNull(WebToolUrlGuard.validatePublicHttpUrl("https://1.1.1.1/path"));
  }
}
