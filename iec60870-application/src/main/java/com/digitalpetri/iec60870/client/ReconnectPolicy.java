package com.digitalpetri.iec60870.client;

import java.time.Duration;
import java.util.Objects;

/**
 * Decides whether and when an {@link Iec60870Client} reconnects after it loses its connection.
 *
 * <p>Once {@link Iec60870Client#connect()} has been called, the client keeps the connection up on
 * its own: after any unplanned loss, and after a failed connection attempt, it waits for the delay
 * this policy gives and then dials again, running the same session initialization as {@code
 * connect()}. It keeps retrying until a connection is established, {@link
 * Iec60870Client#disconnect()} or {@link Iec60870Client#close()} is called, or {@code connect()}
 * starts an attempt at once. Each scheduled attempt is announced with a {@link
 * ClientEvent.ReconnectScheduled} event.
 *
 * <p>The delay before attempt {@code n} is {@code initialDelay * 2^(n - 1)}, capped at {@code
 * maxDelay}. Attempts are counted from the last established connection, so an established
 * connection resets the backoff.
 *
 * <pre>{@code
 * Iec60870Client client = TcpIec104Client.builder()
 *     .host("10.0.0.5")
 *     .reconnectPolicy(ReconnectPolicy.exponentialBackoff(
 *         Duration.ofMillis(500), Duration.ofSeconds(10)))
 *     .build();
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class ReconnectPolicy {

  private static final ReconnectPolicy DISABLED =
      new ReconnectPolicy(false, Duration.ZERO, Duration.ZERO);

  private static final ReconnectPolicy DEFAULTS =
      new ReconnectPolicy(true, Duration.ofSeconds(1), Duration.ofSeconds(32));

  private final boolean enabled;
  private final Duration initialDelay;
  private final Duration maxDelay;

  private ReconnectPolicy(boolean enabled, Duration initialDelay, Duration maxDelay) {
    this.enabled = enabled;
    this.initialDelay = initialDelay;
    this.maxDelay = maxDelay;
  }

  /**
   * Returns a policy that never reconnects.
   *
   * <p>After a loss or a failed attempt the client stays disconnected until the application calls
   * {@link Iec60870Client#connect()} again.
   *
   * @return the disabled policy.
   */
  public static ReconnectPolicy disabled() {
    return DISABLED;
  }

  /**
   * Returns the default policy: exponential backoff from one second up to 32 seconds.
   *
   * @return the default policy.
   */
  public static ReconnectPolicy defaults() {
    return DEFAULTS;
  }

  /**
   * Returns a policy that reconnects with exponential backoff.
   *
   * @param initialDelay the delay before the first attempt after a loss; must be positive.
   * @param maxDelay the largest delay between attempts; must not be less than {@code initialDelay}.
   * @return the policy.
   * @throws NullPointerException if either argument is null.
   * @throws IllegalArgumentException if {@code initialDelay} is not positive or {@code maxDelay} is
   *     less than {@code initialDelay}.
   */
  public static ReconnectPolicy exponentialBackoff(Duration initialDelay, Duration maxDelay) {
    Objects.requireNonNull(initialDelay, "initialDelay");
    Objects.requireNonNull(maxDelay, "maxDelay");
    if (initialDelay.isZero() || initialDelay.isNegative()) {
      throw new IllegalArgumentException("initialDelay must be positive: " + initialDelay);
    }
    if (maxDelay.compareTo(initialDelay) < 0) {
      throw new IllegalArgumentException(
          "maxDelay must not be less than initialDelay: " + maxDelay + " < " + initialDelay);
    }
    return new ReconnectPolicy(true, initialDelay, maxDelay);
  }

  /**
   * Reports whether this policy reconnects at all.
   *
   * @return {@code false} for {@link #disabled()}.
   */
  public boolean enabled() {
    return enabled;
  }

  /**
   * Returns the delay before the first attempt after a loss.
   *
   * @return the initial delay, or {@link Duration#ZERO} for {@link #disabled()}.
   */
  public Duration initialDelay() {
    return initialDelay;
  }

  /**
   * Returns the largest delay between attempts.
   *
   * @return the maximum delay, or {@link Duration#ZERO} for {@link #disabled()}.
   */
  public Duration maxDelay() {
    return maxDelay;
  }

  /**
   * Returns the delay before the given attempt.
   *
   * @param attempt the attempt number, counted from 1 since the last established connection.
   * @return {@code initialDelay * 2^(attempt - 1)}, capped at the maximum delay.
   * @throws IllegalArgumentException if {@code attempt} is less than 1.
   * @throws IllegalStateException if this policy is {@link #disabled()}.
   */
  public Duration delay(int attempt) {
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be >= 1: " + attempt);
    }
    if (!enabled) {
      throw new IllegalStateException("reconnect is disabled");
    }
    Duration delay = initialDelay;
    for (int i = 1; i < attempt && delay.compareTo(maxDelay) < 0; i++) {
      delay = delay.multipliedBy(2);
    }
    return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ReconnectPolicy that
        && enabled == that.enabled
        && initialDelay.equals(that.initialDelay)
        && maxDelay.equals(that.maxDelay);
  }

  @Override
  public int hashCode() {
    return Objects.hash(enabled, initialDelay, maxDelay);
  }

  @Override
  public String toString() {
    return enabled
        ? "ReconnectPolicy[initialDelay=" + initialDelay + ", maxDelay=" + maxDelay + "]"
        : "ReconnectPolicy[disabled]";
  }
}
