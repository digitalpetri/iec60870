package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.asdu.Asdu;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * A positively confirmed selection of a command, from {@link CommandService#select(Command)}.
 *
 * <p>The handle holds the command reservation for its point and command family until the selection
 * ends, so other commands to that point and family fail with {@link
 * com.digitalpetri.iec60870.RequestInProgressException}. End it with exactly one of {@link
 * #execute()}, which operates the output (S/E = 0), or {@link #cancel()}, which breaks off the
 * selection with a deactivation (COT 8). A second call, or a call after the selection expired,
 * fails with {@link IllegalStateException} and sends nothing. After a connection loss, calls fail
 * with {@link com.digitalpetri.iec60870.ConnectionClosedException}; the next connection never
 * inherits a selection.
 *
 * <p>When {@link ClientConfig#selectionTimeout()} elapses without execute or cancel, the client
 * sends one deactivation and releases the reservation. {@link #close()} does the same for a held
 * selection, so try-with-resources cleans up after an aborted operator dialog:
 *
 * <pre>{@code
 * try (SelectedCommand selected = client.commands().select(Command.single(breaker, true))) {
 *   if (operatorConfirms()) {
 *     CommandResult r = selected.execute();
 *   }
 * }
 * }</pre>
 *
 * <p>For a time-tagged command, execute and cancel carry the time they are sent rather than the
 * time of the select. The handle is thread-safe.
 */
public interface SelectedCommand extends AutoCloseable {

  /**
   * Returns the selected command.
   *
   * @return the command.
   */
  Command command();

  /**
   * Returns the positive activation confirmation of the select.
   *
   * @return the ACT_CON.
   */
  Asdu selectConfirmation();

  /**
   * Returns when the client ends the selection if it is neither executed nor cancelled.
   *
   * @return the expiry instant.
   */
  Instant expiresAt();

  /**
   * Sends the execute (S/E = 0) and waits for its result. Completion follows the {@link
   * CommandOptions} given to {@code select}.
   *
   * @return the command result.
   * @throws IllegalStateException if the selection was already executed, cancelled, or expired.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection was lost.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if no confirmation arrives within
   *     the command timeout.
   */
  CommandResult execute();

  /**
   * Sends the execute (S/E = 0) and returns a stage for its result.
   *
   * @return a stage that completes with the command result, or exceptionally as {@link #execute()}
   *     throws.
   */
  CompletionStage<CommandResult> executeAsync();

  /**
   * Sends a deactivation (COT 8) of the select and waits for the deactivation confirmation.
   *
   * <p>The result is {@link CommandOutcome#CONFIRMED} on a positive DEACT_CON and {@link
   * CommandOutcome#REJECTED} on a negative one or a cause 44 to 47 echo. Every outcome, including a
   * timeout, ends the selection.
   *
   * @return the cancel result.
   * @throws IllegalStateException if the selection was already executed, cancelled, or expired.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection was lost.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if no confirmation arrives within
   *     the command timeout.
   */
  CommandResult cancel();

  /**
   * Sends a deactivation (COT 8) of the select and returns a stage for the result.
   *
   * @return a stage that completes with the cancel result, or exceptionally as {@link #cancel()}
   *     throws.
   */
  CompletionStage<CommandResult> cancelAsync();

  /**
   * Cancels the selection if it is still held, without waiting for the deactivation confirmation.
   * Does nothing after execute, cancel, expiry, or connection loss.
   */
  @Override
  void close();
}
