package com.digitalpetri.iec104.client;

import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.asdu.Cause;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a control command.
 *
 * <p>A command completes with a {@code CommandResult} rather than throwing on a protocol-level
 * rejection: {@link #positive()} reports whether the controlled station accepted the command (the
 * confirming ASDU's P/N bit was clear). A {@link #positive()} of {@code false} means the station
 * returned a negative confirmation; the {@link #cause()} and {@link #confirmation()} describe it.
 * Transport and session failures (timeouts, disconnects) are still surfaced as exceptions.
 *
 * @param target the point the command targeted.
 * @param positive {@code true} if the command was confirmed positively, {@code false} on a negative
 *     confirmation.
 * @param cause the cause of transmission of the confirming ASDU.
 * @param confirmation the confirming ASDU, if one was received.
 */
public record CommandResult(
    PointAddress target, boolean positive, Cause cause, Optional<Asdu> confirmation) {

  /**
   * Validates the components of the result.
   *
   * @param target the point the command targeted.
   * @param positive {@code true} if the command was confirmed positively, {@code false} on a
   *     negative confirmation.
   * @param cause the cause of transmission of the confirming ASDU.
   * @param confirmation the confirming ASDU, if one was received.
   * @throws NullPointerException if {@code target}, {@code cause}, or {@code confirmation} is null.
   */
  public CommandResult {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(cause, "cause");
    Objects.requireNonNull(confirmation, "confirmation");
  }
}
