package com.digitalpetri.iec60870.tests;

import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * A tiny awaitility-style polling helper for the integration tests: it repeatedly evaluates a
 * condition until it holds or a bounded deadline elapses, sleeping briefly between attempts.
 *
 * <p>The integration tests drive a real client and server over loopback, so results arrive
 * asynchronously on the libraries' callback executors. Using a bounded poll instead of a fixed
 * {@link Thread#sleep} avoids both flakiness (the test never races a slow callback) and hangs
 * (every wait has a deadline and fails the test on timeout rather than blocking forever).
 */
final class Await {

  /** The default bound applied to every wait, generous enough to absorb CI scheduling jitter. */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

  private Await() {}

  /**
   * Polls {@code condition} until it returns {@code true} or {@link #DEFAULT_TIMEOUT} elapses.
   *
   * @param description a human-readable description of the awaited condition, used in the failure
   *     message.
   * @param condition the condition to poll.
   */
  static void until(String description, BooleanSupplier condition) {
    long deadline = System.nanoTime() + DEFAULT_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      sleep();
    }
    if (condition.getAsBoolean()) {
      return;
    }
    fail("timed out after " + DEFAULT_TIMEOUT.toMillis() + " ms waiting for: " + description);
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while polling", e);
    }
  }
}
