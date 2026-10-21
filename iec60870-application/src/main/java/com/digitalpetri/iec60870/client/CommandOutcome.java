package com.digitalpetri.iec60870.client;

/**
 * How a command procedure ended, as reported by {@link CommandResult#outcome()}.
 *
 * <p>{@link #CONFIRMED} and {@link #TERMINATED} are positive. Every outcome after a positive
 * activation confirmation ({@link #TERMINATED}, {@link #TERMINATED_NEGATIVE}, and {@link
 * #TERMINATION_MISSING}) means the outstation accepted the command, so the caller should not
 * blindly repeat it.
 */
public enum CommandOutcome {

  /** A positive ACT_CON, or a positive DEACT_CON for a cancel; termination was not awaited. */
  CONFIRMED,

  /** A positive ACT_CON, then an ACT_TERM with P/N = 0. */
  TERMINATED,

  /**
   * A negative ACT_CON or DEACT_CON, or an echo of the command with cause 44 to 47 (unknown type,
   * cause, common address, or object address) whatever its P/N bit.
   */
  REJECTED,

  /** A positive ACT_CON, then an ACT_TERM with P/N = 1: the outstation reports failure. */
  TERMINATED_NEGATIVE,

  /**
   * A positive ACT_CON, then no ACT_TERM before the termination timeout or the end of the
   * connection.
   */
  TERMINATION_MISSING
}
