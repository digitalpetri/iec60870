package com.digitalpetri.iec104.client;

import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.element.DoubleCommandState;
import com.digitalpetri.iec104.asdu.element.NormalizedValue;
import com.digitalpetri.iec104.asdu.element.StepCommandState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A control-direction command targeting a single point, expressed in domain terms.
 *
 * <p>A {@code Command} is distinct from the wire-level {@code com.digitalpetri.iec104.asdu.object}
 * command records: it carries the target {@link PointAddress}, the command value, and the command
 * options (the qualifier and an optional CP56Time2a time tag), but it does not fix the
 * select/execute flag. The {@link CommandService} chooses select or execute from the {@link
 * CommandMode} when it builds the ASDU. When a command carries a {@link #time()}, the time-tagged
 * ASDU type (for example C_SC_TA_1) is used; otherwise the untimed type (for example C_SC_NA_1) is
 * used.
 *
 * <p>Use the factory methods to construct commands, or the convenience helpers on {@link
 * CommandService}:
 *
 * <pre>{@code
 * Command cmd = Command.single(point, true);
 * CommandResult r = client.commands().send(cmd, CommandMode.directExecute());
 * }</pre>
 */
public sealed interface Command
    permits Command.SingleCommandRequest,
        Command.DoubleCommandRequest,
        Command.RegulatingStepCommandRequest,
        Command.SetpointNormalizedRequest,
        Command.SetpointScaledRequest,
        Command.SetpointShortFloatRequest,
        Command.BitstringCommandRequest {

  /**
   * Returns the address of the point this command targets.
   *
   * @return the target point address.
   */
  PointAddress target();

  /**
   * Returns the qualifier value to place in the command's qualifier field ({@code QU} for
   * SCO/DCO/RCO commands, {@code QL} for set-point commands), excluding the select/execute flag.
   *
   * @return the qualifier value.
   */
  int qualifier();

  /**
   * Returns the optional CP56Time2a time tag carried by this command.
   *
   * <p>When present the {@link CommandService} selects the time-tagged ASDU type for this command.
   *
   * @return the command time tag, if any.
   */
  Optional<Instant> time();

  /**
   * A single command (C_SC_NA_1 / C_SC_TA_1).
   *
   * @param target the target point address.
   * @param on the single command state; {@code true} commands ON, {@code false} commands OFF.
   * @param qualifier the {@code QU} qualifier value, in the range {@code 0..31}.
   * @param time the optional CP56Time2a time tag.
   */
  record SingleCommandRequest(
      PointAddress target, boolean on, int qualifier, Optional<Instant> time) implements Command {

    /**
     * Validates the components.
     *
     * @param target the target point address.
     * @param on the single command state; {@code true} commands ON, {@code false} commands OFF.
     * @param qualifier the {@code QU} qualifier value, in the range {@code 0..31}.
     * @param time the optional CP56Time2a time tag.
     * @throws NullPointerException if {@code target} or {@code time} is null.
     * @throws IllegalArgumentException if {@code qualifier} is outside {@code 0..31}.
     */
    public SingleCommandRequest {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(time, "time");
      requireQualifier(qualifier, 31);
    }
  }

  /**
   * A double command (C_DC_NA_1 / C_DC_TA_1).
   *
   * @param target the target point address.
   * @param state the double command state.
   * @param qualifier the {@code QU} qualifier value, in the range {@code 0..31}.
   * @param time the optional CP56Time2a time tag.
   */
  record DoubleCommandRequest(
      PointAddress target, DoubleCommandState state, int qualifier, Optional<Instant> time)
      implements Command {

    /**
     * Validates the components.
     *
     * @param target the target point address.
     * @param state the double command state.
     * @param qualifier the {@code QU} qualifier value, in the range {@code 0..31}.
     * @param time the optional CP56Time2a time tag.
     * @throws NullPointerException if {@code target}, {@code state}, or {@code time} is null.
     * @throws IllegalArgumentException if {@code qualifier} is outside {@code 0..31}.
     */
    public DoubleCommandRequest {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(time, "time");
      requireQualifier(qualifier, 31);
    }
  }

  /**
   * A regulating step command (C_RC_NA_1 / C_RC_TA_1).
   *
   * @param target the target point address.
   * @param state the regulating step command state.
   * @param qualifier the {@code QU} qualifier value, in the range {@code 0..31}.
   * @param time the optional CP56Time2a time tag.
   */
  record RegulatingStepCommandRequest(
      PointAddress target, StepCommandState state, int qualifier, Optional<Instant> time)
      implements Command {

    /**
     * Validates the components.
     *
     * @param target the target point address.
     * @param state the regulating step command state.
     * @param qualifier the {@code QU} qualifier value, in the range {@code 0..31}.
     * @param time the optional CP56Time2a time tag.
     * @throws NullPointerException if {@code target}, {@code state}, or {@code time} is null.
     * @throws IllegalArgumentException if {@code qualifier} is outside {@code 0..31}.
     */
    public RegulatingStepCommandRequest {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(time, "time");
      requireQualifier(qualifier, 31);
    }
  }

  /**
   * A normalized set-point command (C_SE_NA_1 / C_SE_TA_1).
   *
   * @param target the target point address.
   * @param value the normalized set-point value.
   * @param qualifier the {@code QL} qualifier value, in the range {@code 0..127}.
   * @param time the optional CP56Time2a time tag.
   */
  record SetpointNormalizedRequest(
      PointAddress target, NormalizedValue value, int qualifier, Optional<Instant> time)
      implements Command {

    /**
     * Validates the components.
     *
     * @param target the target point address.
     * @param value the normalized set-point value.
     * @param qualifier the {@code QL} qualifier value, in the range {@code 0..127}.
     * @param time the optional CP56Time2a time tag.
     * @throws NullPointerException if {@code target}, {@code value}, or {@code time} is null.
     * @throws IllegalArgumentException if {@code qualifier} is outside {@code 0..127}.
     */
    public SetpointNormalizedRequest {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(time, "time");
      requireQualifier(qualifier, 127);
    }
  }

  /**
   * A scaled set-point command (C_SE_NB_1 / C_SE_TB_1).
   *
   * @param target the target point address.
   * @param value the scaled (signed 16-bit) set-point value.
   * @param qualifier the {@code QL} qualifier value, in the range {@code 0..127}.
   * @param time the optional CP56Time2a time tag.
   */
  record SetpointScaledRequest(
      PointAddress target, short value, int qualifier, Optional<Instant> time) implements Command {

    /**
     * Validates the components.
     *
     * @param target the target point address.
     * @param value the scaled (signed 16-bit) set-point value.
     * @param qualifier the {@code QL} qualifier value, in the range {@code 0..127}.
     * @param time the optional CP56Time2a time tag.
     * @throws NullPointerException if {@code target} or {@code time} is null.
     * @throws IllegalArgumentException if {@code qualifier} is outside {@code 0..127}.
     */
    public SetpointScaledRequest {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(time, "time");
      requireQualifier(qualifier, 127);
    }
  }

  /**
   * A short-float set-point command (C_SE_NC_1 / C_SE_TC_1).
   *
   * @param target the target point address.
   * @param value the short floating-point set-point value.
   * @param qualifier the {@code QL} qualifier value, in the range {@code 0..127}.
   * @param time the optional CP56Time2a time tag.
   */
  record SetpointShortFloatRequest(
      PointAddress target, float value, int qualifier, Optional<Instant> time) implements Command {

    /**
     * Validates the components.
     *
     * @param target the target point address.
     * @param value the short floating-point set-point value.
     * @param qualifier the {@code QL} qualifier value, in the range {@code 0..127}.
     * @param time the optional CP56Time2a time tag.
     * @throws NullPointerException if {@code target} or {@code time} is null.
     * @throws IllegalArgumentException if {@code qualifier} is outside {@code 0..127}.
     */
    public SetpointShortFloatRequest {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(time, "time");
      requireQualifier(qualifier, 127);
    }
  }

  /**
   * A 32-bit bit-string command (C_BO_NA_1 / C_BO_TA_1).
   *
   * <p>The bit-string command carries no qualifier on the wire; its {@link #qualifier()} is always
   * {@code 0}.
   *
   * @param target the target point address.
   * @param bits the 32 raw bits to command.
   * @param time the optional CP56Time2a time tag.
   */
  record BitstringCommandRequest(PointAddress target, int bits, Optional<Instant> time)
      implements Command {

    /**
     * Validates the components.
     *
     * @param target the target point address.
     * @param bits the 32 raw bits to command.
     * @param time the optional CP56Time2a time tag.
     * @throws NullPointerException if {@code target} or {@code time} is null.
     */
    public BitstringCommandRequest {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(time, "time");
    }

    /**
     * Returns the bit-string command qualifier, which is always {@code 0}.
     *
     * @return {@code 0}.
     */
    @Override
    public int qualifier() {
      return 0;
    }
  }

  /**
   * Creates a single command with qualifier {@code 0} and no time tag.
   *
   * @param target the target point address.
   * @param on the single command state.
   * @return the command.
   */
  static SingleCommandRequest single(PointAddress target, boolean on) {
    return new SingleCommandRequest(target, on, 0, Optional.empty());
  }

  /**
   * Creates a double command with qualifier {@code 0} and no time tag.
   *
   * @param target the target point address.
   * @param state the double command state.
   * @return the command.
   */
  static DoubleCommandRequest doublePoint(PointAddress target, DoubleCommandState state) {
    return new DoubleCommandRequest(target, state, 0, Optional.empty());
  }

  /**
   * Creates a regulating step command with qualifier {@code 0} and no time tag.
   *
   * @param target the target point address.
   * @param state the regulating step command state.
   * @return the command.
   */
  static RegulatingStepCommandRequest regulatingStep(PointAddress target, StepCommandState state) {
    return new RegulatingStepCommandRequest(target, state, 0, Optional.empty());
  }

  /**
   * Creates a normalized set-point command with qualifier {@code 0} and no time tag.
   *
   * @param target the target point address.
   * @param value the normalized set-point value.
   * @return the command.
   */
  static SetpointNormalizedRequest setpointNormalized(PointAddress target, NormalizedValue value) {
    return new SetpointNormalizedRequest(target, value, 0, Optional.empty());
  }

  /**
   * Creates a scaled set-point command with qualifier {@code 0} and no time tag.
   *
   * @param target the target point address.
   * @param value the scaled set-point value.
   * @return the command.
   */
  static SetpointScaledRequest setpointScaled(PointAddress target, short value) {
    return new SetpointScaledRequest(target, value, 0, Optional.empty());
  }

  /**
   * Creates a short-float set-point command with qualifier {@code 0} and no time tag.
   *
   * @param target the target point address.
   * @param value the short floating-point set-point value.
   * @return the command.
   */
  static SetpointShortFloatRequest setpointShortFloat(PointAddress target, float value) {
    return new SetpointShortFloatRequest(target, value, 0, Optional.empty());
  }

  /**
   * Creates a 32-bit bit-string command with no time tag.
   *
   * @param target the target point address.
   * @param bits the 32 raw bits to command.
   * @return the command.
   */
  static BitstringCommandRequest bitstring(PointAddress target, int bits) {
    return new BitstringCommandRequest(target, bits, Optional.empty());
  }

  /**
   * Validates a qualifier value against an inclusive maximum.
   *
   * @param qualifier the qualifier value.
   * @param max the inclusive maximum.
   * @throws IllegalArgumentException if {@code qualifier} is negative or exceeds {@code max}.
   */
  private static void requireQualifier(int qualifier, int max) {
    if (qualifier < 0 || qualifier > max) {
      throw new IllegalArgumentException("qualifier out of range [0, " + max + "]: " + qualifier);
    }
  }
}
