package com.digitalpetri.iec60870.transport.serial;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architecture guard: the {@code iec60870-transport-serial} octet classes depend on {@code
 * iec60870-core} only.
 *
 * <p>The serial transport is a Netty-free, core-only octet transport (the analog of {@code
 * iec60870-transport-tcp}'s Netty octet classes): it implements the core {@link
 * com.digitalpetri.iec60870.transport.ClientTransport} / {@code ServerTransport} SPI and speaks
 * whole-frame {@code ByteBuf}s, importing nothing from the CS101 link layer or the high-level
 * application layer. This guard, mirroring the {@code NoNettyInApplicationTest} source-text
 * scanner, walks every {@code .java} file under the serial module's main source tree and fails if
 * any of the forbidden packages appears. Comment lines (Javadoc and {@code //}) are ignored, so
 * documentation that <em>names</em> the forbidden packages to explain their absence does not trip
 * the guard.
 *
 * <p><b>Builder exception.</b> The {@code SerialIec101Client} and {@code SerialIec101Server}
 * builders are the <em>sole assembly point</em> for the serial 101 stack: they alone wire the
 * core-only octet transport to the CS101 link layer ({@code Cs101Binding}, {@code LinkSettings})
 * and the high-level application facades ({@code Iec60870Client} / {@code Iec60870Server} and their
 * configs). They therefore legitimately import the otherwise-forbidden packages, exactly as {@code
 * TcpIec104Client} / {@code TcpIec104Server} are the sole 104 assembly point that may reach into
 * {@code cs104} + {@code application}. This guard excludes those two files from the
 * forbidden-import scan; every other source file — in particular all octet classes — must still
 * depend on core only.
 *
 * <p>Note: the {@code com.fazecast} (jSerialComm) driver is <b>not</b> forbidden here — the serial
 * transport legitimately uses it. The "no {@code com.fazecast} type leaks past the transport" rule
 * is instead enforced structurally, by confining the jSerialComm driver to this module's private
 * internals (it never appears in a public signature); the package Javadoc documents that
 * containment rule.
 */
class SerialTransportDependencyGuardTest {

  /**
   * Forbidden tokens: the CS101 link-layer package and the application-layer packages ({@code
   * client}, {@code server}, {@code point}, {@code catalog}). The serial octet classes may depend
   * on core only. {@code com.fazecast} is deliberately absent: the transport may use it internally.
   */
  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.cs101\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.client\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.server\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.point\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.catalog\\b"));

  /**
   * The two builder files excluded from the forbidden-import scan: they are the sole assembly point
   * and legitimately import the CS101 link layer and the application facades. Every other file —
   * notably all octet classes — is still held to the core-only rule.
   */
  private static final Set<String> ASSEMBLY_POINT_FILES =
      Set.of("SerialIec101Client.java", "SerialIec101Server.java");

  @Test
  void serialTransportSourceTreeDependsOnCoreOnly() throws IOException {
    Path serialMain = locateSerialMainSources();
    assertTrue(
        Files.isDirectory(serialMain),
        "could not locate iec60870-transport-serial main sources at "
            + serialMain.toAbsolutePath());

    List<String> violations = new ArrayList<>();

    try (Stream<Path> files = Files.walk(serialMain)) {
      files
          .filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !ASSEMBLY_POINT_FILES.contains(p.getFileName().toString()))
          .forEach(p -> scanFile(p, violations));
    }

    if (!violations.isEmpty()) {
      fail(
          "Forbidden dependencies leaked into iec60870-transport-serial"
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
   * Resolves the {@code iec60870-transport-serial/src/main/java} directory relative to this
   * module's working directory (Maven runs Surefire with the module directory as the working
   * directory).
   *
   * @return the serial transport main source directory.
   */
  private static Path locateSerialMainSources() {
    Path moduleDir = Path.of("").toAbsolutePath();
    // When run from the transport-serial module directory, the sources are directly under it.
    Path here = moduleDir.resolve("src/main/java");
    if (Files.isDirectory(here)) {
      return here;
    }
    // Fallback: if cwd is the repo root.
    return moduleDir.resolve("iec60870-transport-serial").resolve("src/main/java");
  }
}
