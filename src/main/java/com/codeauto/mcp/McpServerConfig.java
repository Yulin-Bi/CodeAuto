package com.codeauto.mcp;

import java.util.List;
import java.util.Map;

public record McpServerConfig(
    String name,
    String command,
    List<String> args,
    String protocol,
    Map<String, String> env,
    String url
) {
  public McpServerConfig(String name, String command, List<String> args) {
    this(name, command, args, "auto");
  }

  public McpServerConfig(String name, String command, List<String> args, String protocol) {
    this(name, command, args, protocol, Map.of(), "");
  }

  public McpServerConfig(String name, String command, List<String> args, String protocol, Map<String, String> env) {
    this(name, command, args, protocol, env, "");
  }

  public boolean isHttp() {
    return url != null && !url.isBlank();
  }
}
