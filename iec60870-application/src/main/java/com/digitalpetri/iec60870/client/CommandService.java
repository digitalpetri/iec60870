package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.element.DoubleCommandState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.StepCommandState;
import java.util.concurrent.CompletionStage;

/**
 * Issues control-direction commands and correlates their confirmations and terminations.
 *
 * <p>Obtain a {@code CommandService} from {@link Iec60870Client#commands()}. The primary methods
 * are {@link #send(Command, CommandMode)} (blocking) and {@link #sendAsync(Command, CommandMode)}
 * (non-blocking); the default helpers cover the common command types with a {@link
 * CommandMode#directExecute()} default. Each call returns a {@link CommandResult} whose {@link
 * CommandResult#outcome()} says how the procedure ended. Transport or session failures before a
 * positive confirmation (timeouts, disconnects) are surfaced as exceptions (or an exceptionally
 * completed stage); a protocol-level rejection is a non-positive {@link CommandResult}.
 *
 * <p>A command completes on the activation confirmation (ACT_CON) of its final phase, or, under
 * {@link CommandCompletion#TERMINATION}, on the activation termination (ACT_TERM) that follows it.
 * The client configuration sets the policy per command family; {@link CommandOptions} overrides it
 * per call. {@link #select(Command)} splits select-before-operate into a select now and an execute
 * or cancel later, through a {@link SelectedCommand} handle.
 *
 * <p>One command per point and command family can be in flight. A second fails with {@link
 * com.digitalpetri.iec60870.RequestInProgressException} until the first completes or its selection
 * ends.
 *
 * <pre>{@code
 * CommandService commands = client.commands();
 * CommandResult r = commands.single(point, true);
 * if (!r.positive()) {
 *   handleRejection(r.cause());
 * }
 * }</pre>
 */
public interface CommandService {

  /**
   * Sends a command using the given mode and the configured completion policy, and waits for its
   * result.
   *
   * @param command the command to send.
   * @param mode the command procedure (direct-execute or select-before-operate).
   * @return the command result.
   * @throws IllegalArgumentException if {@code mode} is {@link CommandMode#selectBeforeOperate()}
   *     for a bit-string command; C_BO carries no select/execute qualifier, so
   *     select-before-operate is not applicable to it.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed before
   *     the final phase is confirmed.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if a confirmation does not arrive
   *     within the configured command timeout.
   * @see #send(Command, CommandMode, CommandOptions)
   */
  default CommandResult send(Command command, CommandMode mode) {
    return send(command, mode, CommandOptions.defaults());
  }

  /**
   * Sends a command using the given mode and the configured completion policy, and returns a stage
   * for its result.
   *
   * @param command the command to send.
   * @param mode the command procedure (direct-execute or select-before-operate).
   * @return a stage that completes with the command result, or completes exceptionally on timeout
   *     or disconnect before the final phase is confirmed.
   * @see #sendAsync(Command, CommandMode, CommandOptions)
   */
  default CompletionStage<CommandResult> sendAsync(Command command, CommandMode mode) {
    return sendAsync(command, mode, CommandOptions.defaults());
  }

  /**
   * Sends a command using the given mode and options, and waits for its result.
   *
   * <p>For {@link CommandMode#selectBeforeOperate()} the service sends the select activation, waits
   * for its confirmation, then sends the execute activation. A rejected select is the final result.
   * The operation reserves its command family and target through both phases. A connection loss
   * ends the operation; reconnecting never resumes its execute phase.
   *
   * <p>Cancelling the stage of {@link #sendAsync(Command, CommandMode, CommandOptions)} takes
   * effect only before the execute is submitted, which happens right after a positive select
   * confirmation. A stage cancelled while the select awaits its confirmation sends a deactivation
   * instead of the execute when that confirmation is positive. Once the execute is submitted,
   * cancelling the stage does not retract it. When an operator must confirm between select and
   * execute, use {@link #select(Command, CommandOptions)} instead. A select that times out is also
   * deactivated, in case the outstation accepted it late. After a deactivation the reservation is
   * held until it is confirmed or times out.
   *
   * <p>With {@link CommandCompletion#CONFIRMATION} the result reflects the final ACT_CON. With
   * {@link CommandCompletion#TERMINATION} a positive final ACT_CON keeps the reservation and the
   * result waits for ACT_TERM: {@link CommandOutcome#TERMINATED}, {@link
   * CommandOutcome#TERMINATED_NEGATIVE}, or {@link CommandOutcome#TERMINATION_MISSING} when the
   * termination timeout passes or the connection ends first.
   *
   * <p>Confirmations and terminations must echo the transmitted command, including its value,
   * qualifier, phase, time tag (compared under {@link ClientConfig#commandTimeTagMatch()}), type,
   * and test flag. The reply's originator address must match the command or be zero (unused). Zero
   * also covers profiles with a one-octet cause of transmission, which omit the originator address.
   * Replies for distinguishable commands or another nonzero originator are ignored. An echo with
   * cause 44 to 47 rejects the command whatever its P/N bit.
   *
   * @param command the command to send.
   * @param mode the command procedure (direct-execute or select-before-operate).
   * @param options per-call overrides of the completion policy.
   * @return the command result.
   * @throws IllegalArgumentException if {@code mode} is {@link CommandMode#selectBeforeOperate()}
   *     for a bit-string command; C_BO carries no select/execute qualifier, so
   *     select-before-operate is not applicable to it.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed before
   *     the final phase is confirmed.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if a confirmation does not arrive
   *     within the configured command timeout.
   */
  CommandResult send(Command command, CommandMode mode, CommandOptions options);

  /**
   * Sends a command using the given mode and options, and returns a stage for its result.
   *
   * @param command the command to send.
   * @param mode the command procedure (direct-execute or select-before-operate).
   * @param options per-call overrides of the completion policy.
   * @return a stage that completes with the command result, or completes exceptionally as {@link
   *     #send(Command, CommandMode, CommandOptions)} throws.
   */
  CompletionStage<CommandResult> sendAsync(
      Command command, CommandMode mode, CommandOptions options);

  /**
   * Sends a select (S/E = 1) with the configured completion policy and returns a handle once the
   * outstation confirms it positively.
   *
   * @param command the command to select.
   * @return the selection handle.
   * @see #select(Command, CommandOptions)
   */
  default SelectedCommand select(Command command) {
    return select(command, CommandOptions.defaults());
  }

  /**
   * Sends a select (S/E = 1) and returns a handle once the outstation confirms it positively.
   *
   * <p>The handle holds the reservation for the point and command family until it is executed,
   * cancelled, or expires, or the connection ends. The options apply to the handle's {@link
   * SelectedCommand#execute()}.
   *
   * <pre>{@code
   * SelectedCommand selected = commands.select(Command.doublePoint(breaker, DoubleCommandState.ON));
   * CommandResult r = operatorConfirms() ? selected.execute() : selected.cancel();
   * }</pre>
   *
   * @param command the command to select.
   * @param options per-call overrides of the completion policy for the execute.
   * @return the selection handle.
   * @throws IllegalArgumentException if {@code command} is a bit-string command, which has no
   *     select/execute qualifier; nothing is sent.
   * @throws com.digitalpetri.iec60870.NegativeConfirmationException if the outstation rejects the
   *     select with a negative ACT_CON or a cause 44 to 47 echo.
   * @throws com.digitalpetri.iec60870.RequestInProgressException if a command to the same point and
   *     family is in flight or selected.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed before
   *     the select is confirmed.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if the confirmation does not arrive
   *     within the configured command timeout.
   */
  SelectedCommand select(Command command, CommandOptions options);

  /**
   * Sends a select (S/E = 1) with the configured completion policy and returns a stage for the
   * handle.
   *
   * @param command the command to select.
   * @return a stage that completes with the selection handle.
   * @see #selectAsync(Command, CommandOptions)
   */
  default CompletionStage<SelectedCommand> selectAsync(Command command) {
    return selectAsync(command, CommandOptions.defaults());
  }

  /**
   * Sends a select (S/E = 1) and returns a stage for the handle.
   *
   * <p>Cancelling the stage before it completes deactivates the selection once the outstation
   * confirms the select. After the stage has completed, end the selection through the handle, for
   * example with {@link SelectedCommand#close()}.
   *
   * @param command the command to select.
   * @param options per-call overrides of the completion policy for the execute.
   * @return a stage that completes with the selection handle, or exceptionally as {@link
   *     #select(Command, CommandOptions)} throws.
   */
  CompletionStage<SelectedCommand> selectAsync(Command command, CommandOptions options);

  /**
   * Sends a single command with direct execute.
   *
   * @param target the target point address.
   * @param on the single command state.
   * @return the command result.
   */
  default CommandResult single(PointAddress target, boolean on) {
    return send(Command.single(target, on), CommandMode.directExecute());
  }

  /**
   * Sends a double command with direct execute.
   *
   * @param target the target point address.
   * @param state the double command state.
   * @return the command result.
   */
  default CommandResult doublePoint(PointAddress target, DoubleCommandState state) {
    return send(Command.doublePoint(target, state), CommandMode.directExecute());
  }

  /**
   * Sends a regulating step command with direct execute.
   *
   * @param target the target point address.
   * @param state the regulating step command state.
   * @return the command result.
   */
  default CommandResult regulatingStep(PointAddress target, StepCommandState state) {
    return send(Command.regulatingStep(target, state), CommandMode.directExecute());
  }

  /**
   * Sends a normalized set-point command with direct execute.
   *
   * @param target the target point address.
   * @param value the normalized set-point value.
   * @return the command result.
   */
  default CommandResult setpointNormalized(PointAddress target, NormalizedValue value) {
    return send(Command.setpointNormalized(target, value), CommandMode.directExecute());
  }

  /**
   * Sends a scaled set-point command with direct execute.
   *
   * @param target the target point address.
   * @param value the scaled set-point value.
   * @return the command result.
   */
  default CommandResult setpointScaled(PointAddress target, short value) {
    return send(Command.setpointScaled(target, value), CommandMode.directExecute());
  }

  /**
   * Sends a short-float set-point command with direct execute.
   *
   * @param target the target point address.
   * @param value the short floating-point set-point value.
   * @return the command result.
   */
  default CommandResult setpointShortFloat(PointAddress target, float value) {
    return send(Command.setpointShortFloat(target, value), CommandMode.directExecute());
  }

  /**
   * Sends a 32-bit bit-string command with direct execute.
   *
   * @param target the target point address.
   * @param bits the 32 raw bits to command.
   * @return the command result.
   */
  default CommandResult bitstring(PointAddress target, int bits) {
    return send(Command.bitstring(target, bits), CommandMode.directExecute());
  }
}
