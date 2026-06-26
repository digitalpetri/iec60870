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
 * Architecture guard: the {@code iec60870-transport-tcp} octet classes depend on {@code
 * iec60870-core} only.
 *
 * <p>The TCP transport is a Netty-backed, core-only octet transport: it implements the core {@link
 * com.digitalpetri.iec60870.transport.ClientTransport} / {@code ServerTransport} SPI and speaks
 * whole-frame {@code ByteBuf}s, importing nothing from the link layers or the high-level
 * application layer. Assembly of the 104 / 101-over-TCP stacks lives in the separate {@code
 * iec60870-tcp} module. This guard, mirroring the {@code NoNettyInApplicationTest} source-text
 * scanner, walks every {@code .java} file under the TCP module's main source tree and fails if any
 * of the forbidden packages appears. Comment lines (Javadoc and {@code //}) are ignored, so
 * documentation that <em>names</em> the forbidden packages to explain their absence does not trip
 * the guard.
 *
 * <p>Unlike the serial guard, there is <b>no</b> assembly-point exception: every source file in
 * this module — in particular all octet classes — must depend on core only. The builders that wired
 * the octet transport to the link/application layers have been relocated to {@code iec60870-tcp}.
 *
 * <p>Note: {@code io.netty.*} is <b>not</b> forbidden here — the TCP transport <em>is</em> Netty.
 * The opposite direction (no Netty in the Netty-free modules) is guarded by {@code
 * NoNettyInApplicationTest} and {@code NoNettyInCoreTest}.
 */
class TcpTransportDependencyGuardTest {

  /**
   * Forbidden tokens: the CS104 / CS101 link-layer packages and the application-layer packages
   * ({@code application}, {@code client}, {@code server}, {@code point}, {@code catalog}). The TCP
   * octet classes may depend on core only. {@code io.netty} is deliberately absent: the transport
   * is Netty-backed.
   */
  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.cs104\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.cs101\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.application\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.client\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.server\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.point\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.catalog\\b"));

  @Test
  void tcpTransportSourceTreeDependsOnCoreOnly() throws IOException {
    Path tcpMain = locateTcpMainSources();
    assertTrue(
        Files.isDirectory(tcpMain),
        "could not locate iec60870-transport-tcp main sources at " + tcpMain.toAbsolutePath());

    List<String> violations = new ArrayList<>();

    try (Stream<Path> files = Files.walk(tcpMain)) {
      files.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, violations));
    }

    if (!violations.isEmpty()) {
      fail(
          "Forbidden dependencies leaked into iec60870-transport-tcp"
              + " (its octet classes must depend on core only):\n"
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
   * Strips line and block-comment markers so that documentation naming the forbidden packages is
   * not treated as a code reference.
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
   * Resolves the {@code iec60870-transport-tcp/src/main/java} directory relative to this module's
   * working directory (Maven runs Surefire with the module directory as the working directory).
   *
   * @return the TCP transport main source directory.
   */
  private static Path locateTcpMainSources() {
    Path moduleDir = Path.of("").toAbsolutePath();
    // When run from the transport-tcp module directory, the sources are directly under it.
    Path here = moduleDir.resolve("src/main/java");
    if (Files.isDirectory(here)) {
      return here;
    }
    // Fallback: if cwd is the repo root.
    return moduleDir.resolve("iec60870-transport-tcp").resolve("src/main/java");
  }
}
