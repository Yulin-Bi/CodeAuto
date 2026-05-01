package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class WebSearchTool implements ToolDefinition {
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

  @Override public String name() { return "web_search"; }
  @Override public String description() { return "Search the web through CODEAUTO_SEARCH_URL."; }
  @Override public JsonNode inputSchema() { return JsonSchemas.objectSchema(); }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String query = JsonSchemas.text(input, "query", JsonSchemas.text(input, "q", ""));
    if (query.isBlank()) return ToolResult.error("query is required");
    String endpoint = System.getenv("CODEAUTO_SEARCH_URL");
    if (endpoint == null || endpoint.isBlank()) {
      return ToolResult.error("web_search requires CODEAUTO_SEARCH_URL, for example https://example/search?q={query}");
    }
    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
    String url = endpoint.contains("{query}")
        ? endpoint.replace("{query}", encoded)
        : endpoint + (endpoint.contains("?") ? "&" : "?") + "q=" + encoded;
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
