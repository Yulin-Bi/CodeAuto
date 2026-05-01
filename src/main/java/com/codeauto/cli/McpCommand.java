package com.codeauto.cli;

import com.codeauto.manage.ManagementStore;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(name = "mcp", description = "Manage MCP server config", subcommands = {
    McpCommand.ListCmd.class,
    McpCommand.AddCmd.class,
    McpCommand.LoginCmd.class,
    McpCommand.LogoutCmd.class,
    McpCommand.RemoveCmd.class
})
public class McpCommand implements Runnable {
  @Override public void run() { CommandLine.usage(this, System.out); }

  @CommandLine.Command(name = "list")
  static class ListCmd implements Callable<Integer> {
    @Override public Integer call() throws Exception {
      ManagementStore store = new ManagementStore();
      System.out.println(store.listObject(store.readMcp()));
      return 0;
    }
  }

  @CommandLine.Command(name = "add")
  static class AddCmd implements Callable<Integer> {
    @CommandLine.Option(names = "--protocol", defaultValue = "auto",
        description = "MCP stdio protocol: auto, content-length, or newline-json")
    String protocol;

    @CommandLine.Option(names = "--env", description = "Environment variable KEY=VALUE passed to the MCP server")
    List<String> env = List.of();

    @CommandLine.Parameters(index = "0") String name;
    @CommandLine.Parameters(index = "1..*", arity = "1..*") List<String> command;

    @Override public Integer call() throws Exception {
      ManagementStore store = new ManagementStore();
      var config = store.readMcp();
      var entry = config.putObject(name);
      entry.put("protocol", protocol);
      entry.put("command", command.getFirst());
      var args = entry.putArray("args");
      command.subList(1, command.size()).forEach(args::add);
      if (!env.isEmpty()) {
        var envNode = entry.putObject("env");
        for (String value : env) {
          int equals = value.indexOf('=');
          if (equals <= 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--env must use KEY=VALUE: " + value);
          }
          envNode.put(value.substring(0, equals), value.substring(equals + 1));
        }
      }
      store.writeMcp(config);
      System.out.println("Added MCP server " + name);
      return 0;
    }
  }

  @CommandLine.Command(name = "login")
  static class LoginCmd implements Callable<Integer> {
    @CommandLine.Parameters(index = "0") String name;
    @CommandLine.Option(names = "--token", required = true, description = "Bearer token for this MCP server")
    String token;

    @Override public Integer call() throws Exception {
      String trimmed = token == null ? "" : token.trim();
      if (trimmed.isBlank()) {
        throw new CommandLine.ParameterException(new CommandLine(this), "--token must not be empty");
      }
      ManagementStore store = new ManagementStore();
      var tokens = store.readMcpTokens();
      tokens.put(name, trimmed);
      store.writeMcpTokens(tokens);
      System.out.println("Stored MCP token for " + name);
      return 0;
    }
  }

  @CommandLine.Command(name = "logout")
  static class LogoutCmd implements Callable<Integer> {
    @CommandLine.Parameters(index = "0") String name;

    @Override public Integer call() throws Exception {
      ManagementStore store = new ManagementStore();
      var tokens = store.readMcpTokens();
      if (!tokens.has(name)) {
        System.out.println("No token found for " + name);
        return 0;
      }
      tokens.remove(name);
      store.writeMcpTokens(tokens);
      System.out.println("Removed MCP token for " + name);
      return 0;
    }
  }

  @CommandLine.Command(name = "remove")
  static class RemoveCmd implements Callable<Integer> {
    @CommandLine.Parameters(index = "0") String name;

    @Override public Integer call() throws Exception {
      ManagementStore store = new ManagementStore();
      var config = store.readMcp();
      config.remove(name);
      store.writeMcp(config);
      System.out.println("Removed MCP server " + name);
      return 0;
    }
  }
}
