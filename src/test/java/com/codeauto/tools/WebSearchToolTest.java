package com.codeauto.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {
  @Test
  void parsesDuckDuckGoHtmlResults() {
    String html = """
        <div class="result">
          <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdocs&amp;rut=abc">
            Example &amp; Docs
          </a>
          <a class="result__snippet" href="#">A useful <b>documentation</b> page.</a>
        </div>
        """;

    String parsed = WebSearchTool.parseDuckDuckGoHtml(html);

    assertTrue(parsed.contains("Example & Docs"));
    assertTrue(parsed.contains("https://example.com/docs"));
    assertTrue(parsed.contains("A useful documentation page."));
  }
}
