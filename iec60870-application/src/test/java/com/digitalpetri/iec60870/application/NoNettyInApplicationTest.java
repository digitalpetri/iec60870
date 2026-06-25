package com.digitalpetri.iec60870.application;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architecture guard: no Netty type leaks into the {@code iec60870-application} main source tree.
 *
 * <p>The high-level application layer depends on {@code iec60870-core} only and must carry
 * <b>zero</b> Netty — not even {@code io.netty.buffer.ByteBuf}. The facades speak {@link
 * com.digitalpetri.iec60870.asdu.Asdu} and {@link com.digitalpetri.iec60870.session.Session}; the
 * wire-frame types and the {@code ByteBuf} octet boundary live in {@code iec60870-cs104} and {@code
 * iec60870-transport-tcp}. This guard, mirroring the core/transport guard, scans every {@code
 * .java} file under the application module's main source tree and fails if any {@code io.netty.*}
 * import or type reference appears. Comment lines (Javadoc and {@code //}) are ignored, so
 * documentation that <em>names</em> the forbidden packages to explain their absence does not trip
 * the guard.
 */
class NoNettyInApplicationTest {

  /**
   * Forbidden tokens: the entire {@code io.netty} namespace (including {@code io.netty.buffer}).
   */
  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("\\bio\\.netty\\b"),
          Pattern.compile("\\bByteBuf\\b"),
          Pattern.compile("\\bByteBufAllocator\\b"),
          Pattern.compile("\\bEventLoopGroup\\b"),
          Pattern.compile("\\bSslHandler\\b"),
          Pattern.compile("\\bChannelHandler\\b"));

  @Test
  void applicationSourceTreeContainsNoNettyTypes() throws IOException {
    Path applicationMain = locateApplicationMainSources();
    assertTrue(
        Files.isDirectory(applicationMain),
        "could not locate iec60870-application main sources at "
            + applicationMain.toAbsolutePath());

    List<String> violations = new ArrayList<>();

    try (Stream<Path> files = Files.walk(applicationMain)) {
      files.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, violations));
    }

    if (!violations.isEmpty()) {
      fail(
          "Netty types leaked into iec60870-application (it must carry zero Netty):\n"
              + String.join("\n", violations));
    }
  }

  private static void scanFile(Path file, List<String> violations) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    int lineNumber = 0;
    for (String raw : lines) {
      lineNumber++;
      String line = stripComment(raw);
      if (line.isBlank()) {
        continue;
      }
      for (Pattern pattern : FORBIDDEN) {
        if (pattern.matcher(line).find()) {
          violations.add(file + ":" + lineNumber + " -> " + raw.trim());
        }
      }
    }
  }

  /**
   * Strips line and block-comment markers so that documentation naming the forbidden types is not
   * treated as a code reference.
   *
   * @param raw the source line.
   * @return the code portion of the line, or empty if the line is purely a comment.
   */
  private static String stripComment(String raw) {
    String line = raw.trim();
    if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) {
      return "";
    }
    int slashSlash = line.indexOf("//");
    if (slashSlash >= 0) {
      line = line.substring(0, slashSlash);
    }
    return line;
  }

  /**
   * Resolves the {@code iec60870-application/src/main/java} directory relative to this module's
   * working directory (Maven runs Surefire with the module directory as the working directory).
   *
   * @return the application main source directory.
   */
  private static Path locateApplicationMainSources() {
    Path moduleDir = Path.of("").toAbsolutePath();
    // When run from the application module directory, the sources are directly under it.
    Path here = moduleDir.resolve("src/main/java");
    if (Files.isDirectory(here)) {
      return here;
    }
    // Fallback: if cwd is the repo root.
    return moduleDir.resolve("iec60870-application").resolve("src/main/java");
  }
}
