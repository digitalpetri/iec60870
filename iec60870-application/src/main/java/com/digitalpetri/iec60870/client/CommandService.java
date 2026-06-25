package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.element.DoubleCommandState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.StepCommandState;
import java.util.concurrent.CompletionStage;

/**
 * Issues control-direction commands and correlates their confirmations.
 *
 * <p>Obtain a {@code CommandService} from {@link Iec60870Client#commands()}. The primary methods
 * are {@link #send(Command, CommandMode)} (blocking) and {@link #sendAsync(Command, CommandMode)}
 * (non-blocking); the default helpers cover the common command types with a {@link
 * CommandMode#directExecute()} default. Each call returns a {@link CommandResult} that reports
 * whether the controlled station confirmed the command positively. Transport or session failures —
 * timeouts, disconnects — are surfaced as exceptions (or an exceptionally completed stage); a
 * protocol-level rejection is a non-positive {@link CommandResult}.
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
   * Sends a command using the given mode and waits for the controlled station's confirmation.
   *
   * <p>For {@link CommandMode#selectBeforeOperate()} the service sends the select activation, waits
   * for its confirmation, then sends the execute activation and waits for its confirmation. The
   * returned result reflects the final (execute) confirmation.
   *
   * @param command the command to send.
   * @param mode the command procedure (direct-execute or select-before-operate).
   * @return the command result.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed while
   *     the command is in flight.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if the confirmation does not arrive
   *     within the configured command timeout.
   */
  CommandResult send(Command command, CommandMode mode);

  /**
   * Sends a command using the given mode and returns a stage for the controlled station's
   * confirmation.
   *
   * @param command the command to send.
   * @param mode the command procedure (direct-execute or select-before-operate).
   * @return a stage that completes with the command result, or completes exceptionally on timeout
   *     or disconnect.
   */
  CompletionStage<CommandResult> sendAsync(Command command, CommandMode mode);

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
