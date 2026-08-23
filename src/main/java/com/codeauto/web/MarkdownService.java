package com.codeauto.web;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Safe CommonMark/GFM rendering for the Web transcript. */
final class MarkdownService {
  private static final List<Extension> EXTENSIONS = List.of(
      TablesExtension.create(),
      StrikethroughExtension.create());
  private static final Parser PARSER = Parser.builder()
      .extensions(EXTENSIONS)
      .build();
  private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
      .extensions(EXTENSIONS)
      .escapeHtml(true)
      .sanitizeUrls(true)
      .build();

  private MarkdownService() {
  }

  static String render(String markdown) {
    if (markdown == null || markdown.isBlank()) return "";
    return RENDERER.render(PARSER.parse(markdown));
  }
}
