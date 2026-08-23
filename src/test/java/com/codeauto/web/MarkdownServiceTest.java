package com.codeauto.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownServiceTest {
  @Test
  void rendersCommonMarkAndGfmTables() {
    String html = MarkdownService.render("""
        ## 总结

        | 工具 | 结果 |
        | --- | --- |
        | `edit_file` | **成功** |

        ---

        > 可回滚
        """);

    assertTrue(html.contains("<h2>总结</h2>"));
    assertTrue(html.contains("<table>"));
    assertTrue(html.contains("<code>edit_file</code>"));
    assertTrue(html.contains("<hr />"));
    assertTrue(html.contains("<blockquote>"));
  }

  @Test
  void escapesRawHtmlAndSanitizesUnsafeLinks() {
    String html = MarkdownService.render("<script>alert(1)</script> [bad](javascript:alert(1))");

    assertFalse(html.contains("<script>"));
    assertFalse(html.contains("href=\"javascript:"));
  }
}
