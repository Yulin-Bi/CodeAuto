package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class WebFetchTool implements ToolDefinition {
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

  @Override public String name() { return "web_fetch"; }
  @Override public String description() { return "Fetch text content from a URL."; }
  @Override public JsonNode inputSchema() { return JsonSchemas.objectSchema(); }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String url = JsonSchemas.text(input, "url", "");
    if (url.isBlank()) return ToolResult.error("url is required");
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .GET()
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    return new ToolResult(response.statusCode() >= 200 && response.statusCode() < 300,
        "status=" + response.statusCode() + "\n" + response.body(), false);
  }
}
