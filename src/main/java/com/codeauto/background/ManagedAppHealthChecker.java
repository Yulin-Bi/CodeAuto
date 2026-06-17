package com.codeauto.background;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class ManagedAppHealthChecker {
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(1))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  private ManagedAppHealthChecker() {
  }

  static boolean isHealthy(String healthUrl, int healthPort) {
    if (healthUrl != null && !healthUrl.isBlank()) {
      return isHealthyUrl(healthUrl);
    }
    if (healthPort > 0) {
      return isHealthyPort("127.0.0.1", healthPort);
    }
    return true;
  }

  static boolean isHealthyUrl(String healthUrl) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(healthUrl))
          .GET()
          .timeout(Duration.ofSeconds(1))
          .build();
      int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      return status >= 200 && status < 400;
    } catch (Exception ignored) {
      return false;
    }
  }

  static boolean isHealthyPort(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 1000);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }
}
