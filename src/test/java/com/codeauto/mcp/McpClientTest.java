package com.codeauto.mcp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientTest {
  @Test
  void writesAndReadsContentLengthFrames() throws Exception {
    String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    McpClient client = new McpClient(
        new McpServerConfig("server", "node", List.of(), "content-length"),
        new ByteArrayInputStream(("Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length
            + "\r\n\r\n" + json).getBytes(StandardCharsets.UTF_8)),
        output);

    client.writeFrame(json);

    String written = output.toString(StandardCharsets.UTF_8);
    assertTrue(written.startsWith("Content-Length: "), written);
    assertTrue(written.endsWith(json), written);
    assertEquals(json, client.readFrame());
  }

  @Test
  void writesAndReadsNewlineJsonFrames() throws Exception {
    String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    McpClient client = new McpClient(
        new McpServerConfig("server", "node", List.of(), "newline-json"),
        new ByteArrayInputStream((json + "\n").getBytes(StandardCharsets.UTF_8)),
        output);

    client.writeFrame(json);

    assertEquals(json + "\n", output.toString(StandardCharsets.UTF_8));
    assertEquals(json, client.readFrame());
  }
}
