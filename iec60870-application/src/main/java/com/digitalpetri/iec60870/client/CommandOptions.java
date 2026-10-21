package com.digitalpetri.iec60870.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-call overrides for a command's completion policy.
 *
 * <p>An empty value falls back to the client's configuration: {@link
 * ClientConfig#commandCompletion()} for single, double, and regulating step commands, {@link
 * ClientConfig#setpointCompletion()} for set-point and bit-string commands, and {@link
 * ClientConfig#commandTerminationTimeout()}.
 *
 * <pre>{@code
 * CommandResult r = client.commands().send(
 *     Command.single(point, true),
 *     CommandMode.directExecute(),
 *     CommandOptions.defaults().withCompletion(CommandCompletion.TERMINATION));
 * }</pre>
 *
 * @param completion when the command's result stage completes, or empty for the configured policy.
 * @param terminationTimeout how long to wait for ACT_TERM after the positive ACT_CON of the final
 *     phase, or empty for the configured timeout.
 */
public record CommandOptions(
    Optional<CommandCompletion> completion, Optional<Duration> terminationTimeout) {

  private static final CommandOptions DEFAULTS =
      new CommandOptions(Optional.empty(), Optional.empty());

  /**
   * Validates the components.
   *
   * @param completion when the command's result stage completes, or empty for the configured
   *     policy.
   * @param terminationTimeout how long to wait for ACT_TERM after the positive ACT_CON of the final
   *     phase, or empty for the configured timeout.
   * @throws NullPointerException if either component is null.
   * @throws IllegalArgumentException if {@code terminationTimeout} is zero or negative.
   */
  public CommandOptions {
    Objects.requireNonNull(completion, "completion");
    Objects.requireNonNull(terminationTimeout, "terminationTimeout");
    terminationTimeout.ifPresent(
        timeout -> {
          if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("terminationTimeout must be positive: " + timeout);
          }
        });
  }

  /**
   * Returns options that use the client's configuration for every setting.
   *
   * @return the default options.
   */
  public static CommandOptions defaults() {
    return DEFAULTS;
  }

  /**
   * Returns a copy of these options with the given completion policy.
   *
   * @param completion the completion policy.
   * @return the new options.
   * @throws NullPointerException if {@code completion} is null.
   */
  public CommandOptions withCompletion(CommandCompletion completion) {
    return new CommandOptions(
        Optional.of(Objects.requireNonNull(completion, "completion")), terminationTimeout);
  }

  /**
   * Returns a copy of these options with the given termination timeout.
   *
   * @param timeout how long to wait for ACT_TERM after the positive ACT_CON of the final phase.
   * @return the new options.
   * @throws NullPointerException if {@code timeout} is null.
   * @throws IllegalArgumentException if {@code timeout} is zero or negative.
   */
  public CommandOptions withTerminationTimeout(Duration timeout) {
    return new CommandOptions(completion, Optional.of(Objects.requireNonNull(timeout, "timeout")));
  }
}
