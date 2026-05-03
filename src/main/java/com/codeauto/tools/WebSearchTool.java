package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebSearchTool implements ToolDefinition {
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

  @Override public String name() { return "web_search"; }
  @Override public String description() { return "Search the web via CODEAUTO_SEARCH_URL or DuckDuckGo HTML fallback."; }
  @Override public JsonNode inputSchema() {
    ObjectNode schema = JsonSchemas.schema();
    ObjectNode props = schema.putObject("properties");
    props.set("query", JsonSchemas.stringProp("Search query"));
    return JsonSchemas.required(schema, "query");
  }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String query = JsonSchemas.text(input, "query", JsonSchemas.text(input, "q", ""));
    if (query.isBlank()) return ToolResult.error("query is required");
    String endpoint = System.getenv("CODEAUTO_SEARCH_URL");
    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
    String url = endpoint == null || endpoint.isBlank()
        ? "https://html.duckduckgo.com/html/?q=" + encoded
        : endpoint.contains("{query}")
            ? endpoint.replace("{query}", encoded)
            : endpoint + (endpoint.contains("?") ? "&" : "?") + "q=" + encoded;
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("User-Agent", "CodeAuto/0.1")
        .GET()
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if ((endpoint == null || endpoint.isBlank()) && response.statusCode() >= 200 && response.statusCode() < 300) {
      String parsed = parseDuckDuckGoHtml(response.body());
      if (!parsed.isBlank()) {
        return ToolResult.ok("status=" + response.statusCode() + "\nprovider=duckduckgo_html\n" + parsed);
      }
    }
    return new ToolResult(response.statusCode() >= 200 && response.statusCode() < 300,
        "status=" + response.statusCode() + "\n" + response.body(), false);
  }

  static String parseDuckDuckGoHtml(String html) {
    if (html == null || html.isBlank()) return "";
    Pattern resultPattern = Pattern.compile(
        "(?is)<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?"
            + "<a[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>");
    Matcher matcher = resultPattern.matcher(html);
    List<String> results = new ArrayList<>();
    int index = 1;
    while (matcher.find() && results.size() < 5) {
      String title = cleanHtml(matcher.group(2));
      String link = normalizeDuckDuckGoUrl(cleanHtml(matcher.group(1)));
      String snippet = cleanHtml(matcher.group(3));
      if (title.isBlank() || link.isBlank()) continue;
      results.add(index++ + ". " + title + "\n   " + link + (snippet.isBlank() ? "" : "\n   " + snippet));
    }
    return String.join("\n\n", results);
  }

  private static String normalizeDuckDuckGoUrl(String url) {
    String decoded = decode(url);
    int marker = decoded.indexOf("uddg=");
    if (marker >= 0) {
      String value = decoded.substring(marker + "uddg=".length());
      int end = value.indexOf('&');
      if (end >= 0) value = value.substring(0, end);
      return decode(value);
    }
    return decoded;
  }

  private static String cleanHtml(String value) {
    return decode(value
        .replaceAll("(?is)<[^>]+>", "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">"))
        .replaceAll("\\s+", " ")
        .trim();
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      return value;
    }
  }
}
