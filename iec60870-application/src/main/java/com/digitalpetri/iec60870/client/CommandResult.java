package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.Cause;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a control command.
 *
 * <p>A command completes with a {@code CommandResult} rather than throwing on a protocol-level
 * rejection. {@link #outcome()} says how the procedure ended and {@link #positive()} whether it
 * succeeded. Once the outstation has confirmed a command positively, a missing or negative
 * termination is also a result, not an exception, so the caller learns that the command was
 * accepted. Transport and session failures before that confirmation (timeouts, disconnects) are
 * still surfaced as exceptions.
 *
 * @param target the point the command targeted.
 * @param outcome how the command procedure ended.
 * @param cause the cause of transmission of the ASDU that decided the outcome: the confirmation, or
 *     the termination for {@link CommandOutcome#TERMINATED} and {@link
 *     CommandOutcome#TERMINATED_NEGATIVE}.
 * @param confirmation the ACT_CON, DEACT_CON, or cause 44 to 47 echo, if one was received.
 * @param termination the ACT_TERM, when termination was awaited and received.
 */
public record CommandResult(
    PointAddress target,
    CommandOutcome outcome,
    Cause cause,
    Optional<Asdu> confirmation,
    Optional<Asdu> termination) {

  /**
   * Validates the components of the result.
   *
   * @param target the point the command targeted.
   * @param outcome how the command procedure ended.
   * @param cause the cause of transmission of the ASDU that decided the outcome.
   * @param confirmation the ACT_CON, DEACT_CON, or cause 44 to 47 echo, if one was received.
   * @param termination the ACT_TERM, when termination was awaited and received.
   * @throws NullPointerException if any component is null.
   */
  public CommandResult {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(cause, "cause");
    Objects.requireNonNull(confirmation, "confirmation");
    Objects.requireNonNull(termination, "termination");
  }

  /**
   * Reports whether the command succeeded.
   *
   * @return {@code true} for {@link CommandOutcome#CONFIRMED} and {@link
   *     CommandOutcome#TERMINATED}.
   */
  public boolean positive() {
    return outcome == CommandOutcome.CONFIRMED || outcome == CommandOutcome.TERMINATED;
  }
}
