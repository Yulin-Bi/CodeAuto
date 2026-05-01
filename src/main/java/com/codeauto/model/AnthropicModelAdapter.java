package com.codeauto.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.config.RuntimeConfig;
import com.codeauto.core.AgentStep;
import com.codeauto.core.ChatMessage;
import com.codeauto.core.ProviderUsage;
import com.codeauto.core.ToolCall;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AnthropicModelAdapter implements ModelAdapter {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final RuntimeConfig config;
  private final ToolRegistry tools;
  private final HttpClient client;

  public AnthropicModelAdapter(RuntimeConfig config, ToolRegistry tools) {
    this.config = config;
    this.tools = tools;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  }

  @Override
  public AgentStep next(List<ChatMessage> messages) throws Exception {
    if (config.baseUrl() == null || config.baseUrl().isBlank()) {
      throw new IllegalStateException("baseUrl is required for Anthropic model mode");
    }
    if (config.authToken() == null || config.authToken().isBlank()) {
      throw new IllegalStateException("authToken is required for Anthropic model mode");
    }

    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", config.model());
    body.put("max_tokens", config.maxOutputTokens());
    ArrayNode anthropicMessages = body.putArray("messages");
    ArrayNode toolDefs = body.putArray("tools");
    StringBuilder system = new StringBuilder();

    for (ToolDefinition tool : tools.list()) {
      ObjectNode toolDef = toolDefs.addObject();
      toolDef.put("name", tool.name());
      toolDef.put("description", tool.description());
      toolDef.set("input_schema", tool.inputSchema());
    }

    for (int i = 0; i < messages.size(); i++) {
      ChatMessage message = messages.get(i);
      if (message instanceof ChatMessage.SystemMessage systemMessage) {
        if (!system.isEmpty()) system.append("\n\n");
        system.append(systemMessage.content());
      } else if (message instanceof ChatMessage.ToolResultMessage) {
        List<ChatMessage.ToolResultMessage> results = new ArrayList<>();
        while (i < messages.size() && messages.get(i) instanceof ChatMessage.ToolResultMessage result) {
          results.add(result);
          i++;
        }
        i--;
        appendToolResults(anthropicMessages, results);
      } else {
        appendMessage(anthropicMessages, message);
      }
    }
    if (!system.isEmpty()) {
      body.put("system", system.toString());
    }

    HttpResponse<String> response = sendWithRetry(body);
    JsonNode json = MAPPER.readTree(response.body());
    return parseStep(json);
  }

  private HttpResponse<String> sendWithRetry(ObjectNode body) throws Exception {
    int attempts = Math.max(1, config.maxRetries() + 1);
    for (int attempt = 1; attempt <= attempts; attempt++) {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(config.baseUrl().replaceAll("/+$", "") + "/v1/messages"))
          .timeout(Duration.ofMinutes(2))
          .header("content-type", "application/json")
          .header("anthropic-version", "2023-06-01")
          .header("x-api-key", config.authToken())
          .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 400) {
        return response;
      }
      if (attempt == attempts || (response.statusCode() != 429 && response.statusCode() < 500)) {
        throw new IllegalStateException("Model request failed: " + response.statusCode() + " " + response.body());
      }
      Thread.sleep(retryDelayMs(attempt, response.headers().firstValue("retry-after").orElse(null)));
    }
    throw new IllegalStateException("Model request failed after retries");
  }

  private static long retryDelayMs(int attempt, String retryAfter) {
    if (retryAfter != null) {
      try {
        return Math.max(0, Long.parseLong(retryAfter) * 1000L);
      } catch (NumberFormatException ignored) {
        // Fall through to exponential backoff.
      }
    }
    return Math.min(8000L, 500L * (1L << Math.max(0, attempt - 1)));
  }

  private static void appendMessage(ArrayNode messages, ChatMessage message) {
    ObjectNode next = messages.addObject();
    if (message instanceof ChatMessage.UserMessage user) {
      next.put("role", "user");
      next.put("content", user.content());
    } else if (message instanceof ChatMessage.AssistantMessage assistant) {
      next.put("role", "assistant");
      next.put("content", assistant.content());
    } else if (message instanceof ChatMessage.AssistantRawMessage raw) {
      next.put("role", "assistant");
      next.set("content", raw.content());
    } else if (message instanceof ChatMessage.AssistantProgressMessage progress) {
      next.put("role", "assistant");
      next.put("content", "<progress>\n" + progress.content() + "\n</progress>");
    } else if (message instanceof ChatMessage.AssistantToolCallMessage call) {
      next.put("role", "assistant");
      ArrayNode content = next.putArray("content");
      ObjectNode block = content.addObject();
      block.put("type", "tool_use");
      block.put("id", call.toolUseId());
      block.put("name", call.toolName());
      block.set("input", call.input());
    } else if (message instanceof ChatMessage.ContextSummaryMessage summary) {
      next.put("role", "user");
      next.put("content", summary.content());
    }
  }

  private static void appendToolResults(ArrayNode messages, List<ChatMessage.ToolResultMessage> results) {
    if (results.isEmpty()) return;
    ObjectNode next = messages.addObject();
    next.put("role", "user");
    ArrayNode content = next.putArray("content");
    for (ChatMessage.ToolResultMessage result : results) {
      ObjectNode block = content.addObject();
      block.put("type", "tool_result");
      block.put("tool_use_id", result.toolUseId());
      block.put("content", result.content());
      block.put("is_error", result.isError());
    }
  }

  private static AgentStep parseStep(JsonNode json) {
    ProviderUsage usage = parseUsage(json.get("usage"));
    List<ToolCall> calls = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (JsonNode block : json.path("content")) {
      String type = block.path("type").asText();
      if ("text".equals(type)) {
        if (!text.isEmpty()) text.append("\n");
        text.append(block.path("text").asText());
      } else if ("tool_use".equals(type)) {
        calls.add(new ToolCall(
            block.path("id").asText(UUID.randomUUID().toString()),
            block.path("name").asText(),
            block.path("input")));
      }
    }
    String content = text.toString().trim();
    if (!calls.isEmpty()) {
      return new AgentStep.ToolCallsStep(
          calls,
          content,
          content.isBlank() ? null : AgentStep.ContentKind.PROGRESS,
          usage,
          json.path("content").deepCopy());
    }
    ParsedText parsed = parseAssistantText(content);
    return new AgentStep.AssistantStep(parsed.content(), parsed.kind(), usage);
  }

  private static ProviderUsage parseUsage(JsonNode usage) {
    if (usage == null || usage.isMissingNode()) return null;
    int input = usage.path("input_tokens").asInt(0)
        + usage.path("cache_creation_input_tokens").asInt(0)
        + usage.path("cache_read_input_tokens").asInt(0);
    int output = usage.path("output_tokens").asInt(0);
    int total = input + output;
    return total <= 0 ? null : new ProviderUsage(input, output, total, "anthropic");
  }

  private static ParsedText parseAssistantText(String content) {
    String trimmed = content == null ? "" : content.trim();
    if (trimmed.startsWith("<progress>")) {
      return new ParsedText(trimmed.replaceFirst("(?is)^<progress>", "").replaceFirst("(?is)</progress>$", "").trim(),
          AgentStep.Kind.PROGRESS);
    }
    if (trimmed.startsWith("<final>")) {
      return new ParsedText(trimmed.replaceFirst("(?is)^<final>", "").replaceFirst("(?is)</final>$", "").trim(),
          AgentStep.Kind.FINAL);
    }
    return new ParsedText(trimmed, AgentStep.Kind.UNSPECIFIED);
  }

  private record ParsedText(String content, AgentStep.Kind kind) {
  }
}
