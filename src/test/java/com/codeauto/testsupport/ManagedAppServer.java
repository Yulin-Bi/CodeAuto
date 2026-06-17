package com.codeauto.testsupport;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

public final class ManagedAppServer {
  private ManagedAppServer() {
  }

  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/", exchange -> {
      byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    new CountDownLatch(1).await();
  }
}
