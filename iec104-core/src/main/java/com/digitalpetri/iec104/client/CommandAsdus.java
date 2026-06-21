package com.digitalpetri.iec104.client;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec104.asdu.element.QualifierOfSetpoint;
import com.digitalpetri.iec104.asdu.object.Bitstring32Command;
import com.digitalpetri.iec104.asdu.object.Bitstring32CommandWithCp56Time;
import com.digitalpetri.iec104.asdu.object.DoubleCommand;
import com.digitalpetri.iec104.asdu.object.DoubleCommandWithCp56Time;
import com.digitalpetri.iec104.asdu.object.RegulatingStepCommand;
import com.digitalpetri.iec104.asdu.object.RegulatingStepCommandWithCp56Time;
import com.digitalpetri.iec104.asdu.object.SetpointNormalized;
import com.digitalpetri.iec104.asdu.object.SetpointNormalizedWithCp56Time;
import com.digitalpetri.iec104.asdu.object.SetpointScaled;
import com.digitalpetri.iec104.asdu.object.SetpointScaledWithCp56Time;
import com.digitalpetri.iec104.asdu.object.SetpointShortFloat;
import com.digitalpetri.iec104.asdu.object.SetpointShortFloatWithCp56Time;
import com.digitalpetri.iec104.asdu.object.SingleCommand;
import com.digitalpetri.iec104.asdu.object.SingleCommandWithCp56Time;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Builds the wire-level {@code com.digitalpetri.iec104.asdu.object} command records from a domain
 * {@link Command}, selecting the untimed or CP56Time2a-tagged type and applying the select/execute
 * flag for the current command phase.
 */
final class CommandAsdus {

  private static final ZoneOffset TIME_ZONE = ZoneOffset.UTC;

  private CommandAsdus() {}

  /**
   * Returns the ASDU type that carries a command, choosing the time-tagged variant when the command
   * carries a CP56Time2a time tag.
   *
   * @param command the command.
   * @return the ASDU type.
   */
  static AsduType typeOf(Command command) {
    boolean timed = command.time().isPresent();
    if (command instanceof Command.SingleCommandRequest) {
      return timed ? AsduType.C_SC_TA_1 : AsduType.C_SC_NA_1;
    }
    if (command instanceof Command.DoubleCommandRequest) {
      return timed ? AsduType.C_DC_TA_1 : AsduType.C_DC_NA_1;
    }
    if (command instanceof Command.RegulatingStepCommandRequest) {
      return timed ? AsduType.C_RC_TA_1 : AsduType.C_RC_NA_1;
    }
    if (command instanceof Command.SetpointNormalizedRequest) {
      return timed ? AsduType.C_SE_TA_1 : AsduType.C_SE_NA_1;
    }
    if (command instanceof Command.SetpointScaledRequest) {
      return timed ? AsduType.C_SE_TB_1 : AsduType.C_SE_NB_1;
    }
    if (command instanceof Command.SetpointShortFloatRequest) {
      return timed ? AsduType.C_SE_TC_1 : AsduType.C_SE_NC_1;
    }
    if (command instanceof Command.BitstringCommandRequest) {
      return timed ? AsduType.C_BO_TA_1 : AsduType.C_BO_NA_1;
    }
    throw new IllegalArgumentException("unsupported command: " + command.getClass().getName());
  }

  /**
   * Builds the wire-level information object for a command, applying the select/execute flag.
   *
   * @param command the command.
   * @param select {@code true} for the select phase (S/E = 1), {@code false} for execute (S/E = 0).
   * @return the wire-level information object.
   */
  static InformationObject toObject(Command command, boolean select) {
    InformationObjectAddress ioa = command.target().objectAddress();
    Optional<Instant> time = command.time();

    if (command instanceof Command.SingleCommandRequest c) {
      QualifierOfCommand qoc = new QualifierOfCommand(c.qualifier(), select);
      return time.isPresent()
          ? new SingleCommandWithCp56Time(ioa, c.on(), qoc, cp56(time.get()))
          : new SingleCommand(ioa, c.on(), qoc);
    }
    if (command instanceof Command.DoubleCommandRequest c) {
      QualifierOfCommand qoc = new QualifierOfCommand(c.qualifier(), select);
      return time.isPresent()
          ? new DoubleCommandWithCp56Time(ioa, c.state(), qoc, cp56(time.get()))
          : new DoubleCommand(ioa, c.state(), qoc);
    }
    if (command instanceof Command.RegulatingStepCommandRequest c) {
      QualifierOfCommand qoc = new QualifierOfCommand(c.qualifier(), select);
      return time.isPresent()
          ? new RegulatingStepCommandWithCp56Time(ioa, c.state(), qoc, cp56(time.get()))
          : new RegulatingStepCommand(ioa, c.state(), qoc);
    }
    if (command instanceof Command.SetpointNormalizedRequest c) {
      QualifierOfSetpoint qos = new QualifierOfSetpoint(c.qualifier(), select);
      return time.isPresent()
          ? new SetpointNormalizedWithCp56Time(ioa, c.value(), qos, cp56(time.get()))
          : new SetpointNormalized(ioa, c.value(), qos);
    }
    if (command instanceof Command.SetpointScaledRequest c) {
      QualifierOfSetpoint qos = new QualifierOfSetpoint(c.qualifier(), select);
      return time.isPresent()
          ? new SetpointScaledWithCp56Time(ioa, c.value(), qos, cp56(time.get()))
          : new SetpointScaled(ioa, c.value(), qos);
    }
    if (command instanceof Command.SetpointShortFloatRequest c) {
      QualifierOfSetpoint qos = new QualifierOfSetpoint(c.qualifier(), select);
      return time.isPresent()
          ? new SetpointShortFloatWithCp56Time(ioa, c.value(), qos, cp56(time.get()))
          : new SetpointShortFloat(ioa, c.value(), qos);
    }
    if (command instanceof Command.BitstringCommandRequest c) {
      return time.isPresent()
          ? new Bitstring32CommandWithCp56Time(ioa, c.bits(), cp56(time.get()))
          : new Bitstring32Command(ioa, c.bits());
    }
    throw new IllegalArgumentException("unsupported command: " + command.getClass().getName());
  }

  private static Cp56Time2a cp56(Instant instant) {
    return Cp56Time2a.from(instant, TIME_ZONE);
  }
}
