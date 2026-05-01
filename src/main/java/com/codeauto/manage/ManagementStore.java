package com.codeauto.manage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeauto.config.RuntimeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public class ManagementStore {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public ObjectNode readMcp() throws Exception {
    return readObject(RuntimeConfig.homeDir().resolve("mcp.json"));
  }

  public void writeMcp(ObjectNode config) throws Exception {
    writeObject(RuntimeConfig.homeDir().resolve("mcp.json"), config);
  }

  public ObjectNode readMcpTokens() throws Exception {
    return readObject(RuntimeConfig.homeDir().resolve("mcp-tokens.json"));
  }

  public void writeMcpTokens(ObjectNode tokens) throws Exception {
    writeObject(RuntimeConfig.homeDir().resolve("mcp-tokens.json"), tokens);
  }

  public ObjectNode readSkills() throws Exception {
    return readObject(RuntimeConfig.homeDir().resolve("skills.json"));
  }

  public void writeSkills(ObjectNode config) throws Exception {
    writeObject(RuntimeConfig.homeDir().resolve("skills.json"), config);
  }

  public String listObject(ObjectNode node) {
    if (node.isEmpty()) return "(none)";
    StringBuilder out = new StringBuilder();
    for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
      String name = it.next();
      out.append(name).append(": ").append(node.get(name)).append(System.lineSeparator());
    }
    return out.toString().trim();
  }

  private static ObjectNode readObject(Path path) throws Exception {
    if (!Files.exists(path)) {
      return MAPPER.createObjectNode();
    }
    return (ObjectNode) MAPPER.readTree(path.toFile());
  }

  private static void writeObject(Path path, ObjectNode node) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n");
  }
}
