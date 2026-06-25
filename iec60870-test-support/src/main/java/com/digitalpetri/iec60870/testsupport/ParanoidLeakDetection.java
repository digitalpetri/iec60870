package com.digitalpetri.iec60870.testsupport;

import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetector.Level;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Globally-applied JUnit 5 extension that switches Netty's {@link ResourceLeakDetector} to {@link
 * Level#PARANOID} for the whole test run.
 *
 * <p>It is auto-registered per consuming module via {@code ServiceLoader}: a module opts in by
 * shipping a {@code META-INF/services/org.junit.jupiter.api.extension.Extension} resource naming
 * this class, with extension auto-detection enabled in {@code junit-platform.properties}, so it
 * applies to every test class in that module without per-class annotation. With PARANOID detection
 * on, any {@code ByteBuf} that is garbage collected without being released surfaces loudly as a
 * {@code LEAK:} log line, turning a leaked frame buffer into a visible failure signal during the
 * build.
 */
public final class ParanoidLeakDetection implements BeforeAllCallback {

  @Override
  public void beforeAll(ExtensionContext context) {
    ResourceLeakDetector.setLevel(Level.PARANOID);
  }
}
