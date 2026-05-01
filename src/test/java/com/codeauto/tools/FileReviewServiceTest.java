package com.codeauto.tools;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReviewServiceTest {
  @Test
  void generatesStandardUnifiedDiffWithHunkRanges() {
    String diff = FileReviewService.unifiedDiff(
        Path.of("example.txt"),
        "one\ntwo\nthree\nfour\nfive\n",
        "one\ntwo changed\nthree\nfour\nfive\n");

    assertTrue(diff.startsWith("--- example.txt\n+++ example.txt\n"), diff);
    assertTrue(diff.contains("@@ -1,5 +1,5 @@"), diff);
    assertTrue(diff.contains("-two"), diff);
    assertTrue(diff.contains("+two changed"), diff);
    assertFalse(diff.contains("\n@@\n"), diff);
  }
}
