package com.codeauto.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.codeauto.tool.ToolContext;
import com.codeauto.tool.ToolDefinition;
import com.codeauto.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PatchFileTool implements ToolDefinition {
  @Override public String name() { return "patch_file"; }
  @Override public String description() { return "Apply a simple single-file unified patch."; }
  @Override public JsonNode inputSchema() { return JsonSchemas.objectSchema(); }

  @Override
  public ToolResult run(JsonNode input, ToolContext context) throws Exception {
    String patch = JsonSchemas.text(input, "patch", "");
    if (patch.isBlank()) return ToolResult.error("patch is required");
    ParsedPatch parsed = parsePatch(patch);
    String rawPath = JsonSchemas.text(input, "path", parsed.path());
    if (rawPath == null || rawPath.isBlank()) return ToolResult.error("path is required or must be present in patch header");
    Path file = context.cwd().resolve(stripPrefix(rawPath)).normalize();
    if (!Files.exists(file)) return ToolResult.error("File does not exist: " + file);
    String before = Files.readString(file);
    String after = apply(parsed, before);
    return FileReviewService.reviewAndWrite(file, before, after, context, "Patched");
  }

  private static ParsedPatch parsePatch(String patch) {
    String[] lines = patch.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    String path = "";
    List<String> body = new ArrayList<>();
    boolean inHunk = false;
    for (String line : lines) {
      if (line.startsWith("+++ ")) {
        path = stripPrefix(line.substring(4).trim());
        continue;
      }
      if (line.startsWith("@@")) {
        inHunk = true;
        continue;
      }
      if (inHunk && !line.isEmpty()) {
        char marker = line.charAt(0);
        if (marker == ' ' || marker == '+' || marker == '-') {
          body.add(line);
        }
      }
    }
    return new ParsedPatch(path, body);
  }

  private static String apply(ParsedPatch patch, String before) {
    String lineSeparator = before.contains("\r\n") ? "\r\n" : "\n";
    List<String> original = new ArrayList<>(before.replace("\r\n", "\n").replace('\r', '\n').lines().toList());
    List<String> output = new ArrayList<>();
    int cursor = 0;
    for (String line : patch.lines()) {
      char marker = line.charAt(0);
      String text = line.substring(1);
      if (marker == ' ') {
        int index = indexOf(original, text, cursor, true);
        if (index < 0) throw new IllegalArgumentException("Patch context not found: " + text);
        while (cursor < index) output.add(original.get(cursor++));
        output.add(original.get(cursor++));
      } else if (marker == '-') {
        int index = indexOf(original, text, cursor, true);
        if (index < 0) throw new IllegalArgumentException("Patch removal line not found: " + text);
        while (cursor < index) output.add(original.get(cursor++));
        cursor++;
      } else if (marker == '+') {
        output.add(text);
      }
    }
    while (cursor < original.size()) output.add(original.get(cursor++));
    return String.join(lineSeparator, output) + (before.endsWith("\n") ? lineSeparator : "");
  }

  private static int indexOf(List<String> lines, String text, int start, boolean allowTrailingWhitespaceDifference) {
    for (int i = start; i < lines.size(); i++) {
      if (lines.get(i).equals(text)) return i;
      if (allowTrailingWhitespaceDifference && lines.get(i).stripTrailing().equals(text.stripTrailing())) {
        return i;
      }
    }
    return -1;
  }

  private static String stripPrefix(String path) {
    String value = path;
    if (value.startsWith("a/") || value.startsWith("b/")) {
      value = value.substring(2);
    }
    return value;
  }

  private record ParsedPatch(String path, List<String> lines) {
  }
}
