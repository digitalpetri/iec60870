package com.digitalpetri.iec60870.server;

/**
 * The select/execute phase of a control command received by the server, derived from the command's
 * qualifier of command (the S/E bit).
 *
 * <p>In the select-before-operate procedure a controlling station first sends a command with {@link
 * #SELECT} (S/E = 1) to reserve the output, then a second command with {@link #EXECUTE} (S/E = 0)
 * to perform it. A direct-execute command arrives once with {@link #EXECUTE}. The server applies a
 * point's value update only on an accepted {@link #EXECUTE} command.
 */
public enum CommandMode {

  /** The command is a selection (S/E = 1): reserve the output without operating it. */
  SELECT,

  /** The command is an execution (S/E = 0): operate the output. */
  EXECUTE
}
