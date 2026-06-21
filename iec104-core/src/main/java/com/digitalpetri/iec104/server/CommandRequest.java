package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.InformationObject;
import java.util.Objects;

/**
 * A received control command (one of C_SC/C_DC/C_RC/C_SE/C_BO, timed or untimed) directed at a
 * point.
 *
 * <p>Passed to {@link ServerHandler#onCommand(ServerContext, CommandRequest)}. The {@link
 * #commandObject()} is the raw control information object carrying the command's value and
 * qualifier; pattern-match on its concrete type (for example {@link
 * com.digitalpetri.iec104.asdu.object.SingleCommand}) to read it. {@link #mode()} reports whether
 * the command is a selection or an execution. The handler answers with a {@link CommandDecision}.
 *
 * @param target the fully qualified address of the commanded point.
 * @param type the type identification of the received command ASDU.
 * @param commandObject the received control information object.
 * @param mode the select/execute phase of the command.
 */
public record CommandRequest(
    PointAddress target, AsduType type, InformationObject commandObject, CommandMode mode) {

  /**
   * Validates the components.
   *
   * @param target the fully qualified address of the commanded point.
   * @param type the type identification of the received command ASDU.
   * @param commandObject the received control information object.
   * @param mode the select/execute phase of the command.
   * @throws NullPointerException if any component is null.
   */
  public CommandRequest {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(commandObject, "commandObject");
    Objects.requireNonNull(mode, "mode");
  }

  /**
   * Reports whether this command is a selection (S/E = 1) rather than an execution.
   *
   * @return {@code true} if the command's mode is {@link CommandMode#SELECT}.
   */
  public boolean isSelect() {
    return mode == CommandMode.SELECT;
  }
}
