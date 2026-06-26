package com.digitalpetri.iec60870.cs101;

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
 * Architecture guard: the {@code iec60870-cs101} link layer depends on {@code iec60870-core} only.
 *
 * <p>The CS101 FT1.2 link/session code (the {@link Ft12Frame} / {@link LinkControlField} / {@link
 * Ft12Framer} / {@link LinkSettings} model and the future {@code Ft12LinkLayer}) sits a peer of the
 * {@code iec60870-cs104} {@code ApciSession}: it speaks {@link com.digitalpetri.iec60870.asdu.Asdu}
 * and the {@link com.digitalpetri.iec60870.session.Session} SPI from core and must import nothing
 * from the serial or TCP transports, nor from the high-level application layer. This guard,
 * mirroring the core/transport and {@code NoNettyInApplicationTest} guards, scans every {@code
 * .java} file under the cs101 module's main source tree and fails if any of the forbidden packages
 * appears. Comment lines (Javadoc and {@code //}) are ignored, so documentation that <em>names</em>
 * the forbidden packages to explain their absence does not trip the guard.
 */
class Cs101DependencyGuardTest {

  /**
   * Forbidden tokens: the serial and TCP transport packages and the application-layer packages
   * ({@code client}, {@code server}, {@code point}, {@code catalog}). cs101 may depend on core
   * only.
   */
  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.transport\\.serial\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.transport\\.tcp\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.client\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.server\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.point\\b"),
          Pattern.compile("\\bcom\\.digitalpetri\\.iec60870\\.catalog\\b"));

  @Test
  void cs101SourceTreeDependsOnCoreOnly() throws IOException {
    Path cs101Main = locateCs101MainSources();
    assertTrue(
        Files.isDirectory(cs101Main),
        "could not locate iec60870-cs101 main sources at " + cs101Main.toAbsolutePath());

    List<String> violations = new ArrayList<>();

    try (Stream<Path> files = Files.walk(cs101Main)) {
      files.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, violations));
    }

    if (!violations.isEmpty()) {
      fail(
          "Forbidden dependencies leaked into iec60870-cs101 (it must depend on core only):\n"
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
   * Resolves the {@code iec60870-cs101/src/main/java} directory relative to this module's working
   * directory (Maven runs Surefire with the module directory as the working directory).
   *
   * @return the cs101 main source directory.
   */
  private static Path locateCs101MainSources() {
    Path moduleDir = Path.of("").toAbsolutePath();
    // When run from the cs101 module directory, the sources are directly under it.
    Path here = moduleDir.resolve("src/main/java");
    if (Files.isDirectory(here)) {
      return here;
    }
    // Fallback: if cwd is the repo root.
    return moduleDir.resolve("iec60870-cs101").resolve("src/main/java");
  }
}
