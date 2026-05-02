package com.codeauto;

import com.codeauto.cli.CodeAutoCli;
import java.nio.charset.Charset;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CodeAutoCliEncodingTest {
  @Test
  void stdinCharsetCanBeOverriddenBySystemProperty() {
    String previous = System.getProperty("codeauto.cli.charset");
    try {
      System.setProperty("codeauto.cli.charset", "UTF-8");

      assertEquals(Charset.forName("UTF-8"), CodeAutoCli.stdinCharset());
    } finally {
      if (previous == null) {
        System.clearProperty("codeauto.cli.charset");
      } else {
        System.setProperty("codeauto.cli.charset", previous);
      }
    }
  }

  @Test
  void stdinCharsetAlwaysHasFallback() {
    String previous = System.getProperty("codeauto.cli.charset");
    try {
      System.setProperty("codeauto.cli.charset", "not-a-real-charset");

      assertNotNull(CodeAutoCli.stdinCharset());
    } finally {
      if (previous == null) {
        System.clearProperty("codeauto.cli.charset");
      } else {
        System.setProperty("codeauto.cli.charset", previous);
      }
    }
  }

  @Test
  void bundledBinDirectoryResolvesToProjectRoot() throws Exception {
    java.nio.file.Path project = Files.createTempDirectory("codeauto-cli-project");
    Files.createDirectories(project.resolve("bin"));
    Files.createDirectories(project.resolve("src").resolve("main").resolve("java").resolve("com").resolve("codeauto"));
    Files.writeString(project.resolve("pom.xml"), "<project />");

    assertEquals(project.toAbsolutePath().normalize(),
        CodeAutoCli.projectRootForBundledBin(project.resolve("bin")));
  }
}
