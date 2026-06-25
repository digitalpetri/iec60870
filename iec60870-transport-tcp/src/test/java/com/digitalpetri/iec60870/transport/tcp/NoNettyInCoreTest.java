package com.digitalpetri.iec60870.transport.tcp;

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
 * Architecture guard: no Netty runtime type leaks into the {@code iec60870-core} source tree.
 *
 * <p>The library's central rule (CONTRACT.md, D2) is that Netty {@code Channel}, {@code
 * EventLoopGroup}, {@code SslHandler}, and the {@code io.netty.channel.*}/{@code
 * io.netty.handler.*} packages live only in this transport module. The single allowed exception is
 * {@code io.netty.buffer.ByteBuf}, the sanctioned codec-boundary type: it appears inside the
 * co-located {@code Serde} codecs in core's {@code .asdu}/{@code .address} packages and on the
 * octet transport SPI in {@code .transport} (the deliberate {@code netty-buffer} dependency).
 *
 * <p>This test scans every {@code .java} file under the core module's main source tree and fails if
 * a forbidden import or type reference appears. Comment lines (Javadoc and {@code //}) are ignored,
 * so the documentation in {@code transport/package-info.java} that <em>names</em> the forbidden
 * types to explain their absence does not trip the guard.
 */
class NoNettyInCoreTest {

  /** Forbidden tokens; {@code io.netty.buffer} (ByteBuf) is deliberately excluded. */
  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("\\bio\\.netty\\.channel\\b"),
          Pattern.compile("\\bio\\.netty\\.handler\\b"),
          Pattern.compile("\\bio\\.netty\\.bootstrap\\b"),
          Pattern.compile("\\bEventLoopGroup\\b"),
          Pattern.compile("\\bSslHandler\\b"),
          Pattern.compile("\\bChannelHandler\\b"),
          // Netty's Channel, but not the SocketChannel/etc. that core never references; the bare
          // word is enough for this tree.
          Pattern.compile("\\bio\\.netty\\.channel\\.Channel\\b"));

  @Test
  void coreSourceTreeContainsNoNettyRuntimeTypes() throws IOException {
    Path coreMain = locateCoreMainSources();
    assertTrue(
        Files.isDirectory(coreMain),
        "could not locate iec60870-core main sources at " + coreMain.toAbsolutePath());

    List<String> violations = new ArrayList<>();

    try (Stream<Path> files = Files.walk(coreMain)) {
      files.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, violations));
    }

    if (!violations.isEmpty()) {
      fail(
          "Netty runtime types leaked into iec60870-core (only io.netty.buffer.ByteBuf is allowed):\n"
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
   * Strips line and block-comment markers so that documentation naming the forbidden types (for
   * example in {@code package-info.java}) is not treated as a code reference.
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
   * Resolves the {@code iec60870-core/src/main/java} directory relative to this module's working
   * directory (Maven runs Surefire with the module directory as the working directory).
   *
   * @return the core main source directory.
   */
  private static Path locateCoreMainSources() {
    Path moduleDir = Path.of("").toAbsolutePath();
    // From iec60870-transport-tcp, the sibling core module is one level up.
    Path sibling = moduleDir.resolveSibling("iec60870-core").resolve("src/main/java");
    if (Files.isDirectory(sibling)) {
      return sibling;
    }
    // Fallback: if cwd is the repo root.
    return moduleDir.resolve("iec60870-core").resolve("src/main/java");
  }
}
