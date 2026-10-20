package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.element.QualifierOfSetpoint;
import com.digitalpetri.iec60870.asdu.object.Bitstring32Command;
import com.digitalpetri.iec60870.asdu.object.Bitstring32CommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.DoubleCommand;
import com.digitalpetri.iec60870.asdu.object.DoubleCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.RegulatingStepCommand;
import com.digitalpetri.iec60870.asdu.object.RegulatingStepCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SetpointNormalized;
import com.digitalpetri.iec60870.asdu.object.SetpointNormalizedWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SetpointScaled;
import com.digitalpetri.iec60870.asdu.object.SetpointScaledWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SetpointShortFloat;
import com.digitalpetri.iec60870.asdu.object.SetpointShortFloatWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp56TimeTagged;
import com.digitalpetri.iec60870.point.TimePolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Builds the wire-level {@code com.digitalpetri.iec60870.asdu.object} command records from a domain
 * {@link Command}, selecting the untimed or CP56Time2a-tagged type and applying the select/execute
 * flag for the current command phase, and matches a station's echo against the sent records.
 */
final class CommandAsdus {

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
   * @param policy the policy that encodes the command's time tag.
   * @return the wire-level information object.
   * @throws IllegalArgumentException if {@code select} is requested for a bit-string command; C_BO
   *     carries no select/execute qualifier, so select-before-operate is not applicable to it.
   */
  static InformationObject toObject(Command command, boolean select, TimePolicy policy) {
    InformationObjectAddress ioa = command.target().objectAddress();
    Optional<Instant> time = command.time();

    if (command instanceof Command.SingleCommandRequest c) {
      QualifierOfCommand qoc = new QualifierOfCommand(c.qualifier(), select);
      return time.isPresent()
          ? new SingleCommandWithCp56Time(ioa, c.on(), qoc, policy.toCp56(time.get()))
          : new SingleCommand(ioa, c.on(), qoc);
    }
    if (command instanceof Command.DoubleCommandRequest c) {
      QualifierOfCommand qoc = new QualifierOfCommand(c.qualifier(), select);
      return time.isPresent()
          ? new DoubleCommandWithCp56Time(ioa, c.state(), qoc, policy.toCp56(time.get()))
          : new DoubleCommand(ioa, c.state(), qoc);
    }
    if (command instanceof Command.RegulatingStepCommandRequest c) {
      QualifierOfCommand qoc = new QualifierOfCommand(c.qualifier(), select);
      return time.isPresent()
          ? new RegulatingStepCommandWithCp56Time(ioa, c.state(), qoc, policy.toCp56(time.get()))
          : new RegulatingStepCommand(ioa, c.state(), qoc);
    }
    if (command instanceof Command.SetpointNormalizedRequest c) {
      QualifierOfSetpoint qos = new QualifierOfSetpoint(c.qualifier(), select);
      return time.isPresent()
          ? new SetpointNormalizedWithCp56Time(ioa, c.value(), qos, policy.toCp56(time.get()))
          : new SetpointNormalized(ioa, c.value(), qos);
    }
    if (command instanceof Command.SetpointScaledRequest c) {
      QualifierOfSetpoint qos = new QualifierOfSetpoint(c.qualifier(), select);
      return time.isPresent()
          ? new SetpointScaledWithCp56Time(ioa, c.value(), qos, policy.toCp56(time.get()))
          : new SetpointScaled(ioa, c.value(), qos);
    }
    if (command instanceof Command.SetpointShortFloatRequest c) {
      QualifierOfSetpoint qos = new QualifierOfSetpoint(c.qualifier(), select);
      return time.isPresent()
          ? new SetpointShortFloatWithCp56Time(ioa, c.value(), qos, policy.toCp56(time.get()))
          : new SetpointShortFloat(ioa, c.value(), qos);
    }
    if (command instanceof Command.BitstringCommandRequest c) {
      // C_BO has no select/execute qualifier: a "select" bit-string would encode as a flagless
      // activation indistinguishable from execute, so the station would operate the output during
      // both phases. Reject select-before-operate rather than silently execute twice.
      if (select) {
        throw new IllegalArgumentException(
            "select-before-operate is not applicable to bit-string commands: "
                + "C_BO carries no select/execute qualifier");
      }
      return time.isPresent()
          ? new Bitstring32CommandWithCp56Time(ioa, c.bits(), policy.toCp56(time.get()))
          : new Bitstring32Command(ioa, c.bits());
    }
    throw new IllegalArgumentException("unsupported command: " + command.getClass().getName());
  }

  /**
   * Returns whether a station's echo carries the same command objects that were sent.
   *
   * @param echo the objects of the received confirmation.
   * @param sent the objects of the sent activation.
   * @param match how the time tags of time-tagged commands are compared.
   * @return {@code true} if every echoed object matches the sent object at the same position.
   */
  static boolean echoes(
      List<InformationObject> echo, List<InformationObject> sent, TimeTagMatch match) {
    if (echo.size() != sent.size()) {
      return false;
    }
    for (int i = 0; i < echo.size(); i++) {
      if (!echoes(echo.get(i), sent.get(i), match)) {
        return false;
      }
    }
    return true;
  }

  private static boolean echoes(
      InformationObject echo, InformationObject sent, TimeTagMatch match) {
    if (match == TimeTagMatch.EXACT
        || !(echo instanceof Cp56TimeTagged echoTag)
        || !(sent instanceof Cp56TimeTagged sentTag)) {
      return echo.equals(sent);
    }
    // Compare the tags under the match mode, then the rest of the record with the tag aligned.
    return timeMatches(echoTag.time(), sentTag.time(), match)
        && withTime(echo, sentTag.time()).equals(sent);
  }

  private static boolean timeMatches(Cp56Time2a echo, Cp56Time2a sent, TimeTagMatch match) {
    return switch (match) {
      case EXACT -> echo.equals(sent);
      case INSTANT ->
          echo.milliseconds() == sent.milliseconds()
              && echo.minute() == sent.minute()
              && echo.hour() == sent.hour()
              && echo.dayOfMonth() == sent.dayOfMonth()
              && echo.month() == sent.month()
              && echo.year() == sent.year();
      case IGNORE -> true;
    };
  }

  /**
   * Returns a copy of a time-tagged command record with its time tag replaced, or the record
   * unchanged if it is not a time-tagged command this class builds.
   *
   * @param object the command record.
   * @param time the replacement time tag.
   * @return the record with {@code time} as its time tag.
   */
  private static InformationObject withTime(InformationObject object, Cp56Time2a time) {
    if (object instanceof SingleCommandWithCp56Time o) {
      return new SingleCommandWithCp56Time(o.address(), o.on(), o.qualifier(), time);
    }
    if (object instanceof DoubleCommandWithCp56Time o) {
      return new DoubleCommandWithCp56Time(o.address(), o.state(), o.qualifier(), time);
    }
    if (object instanceof RegulatingStepCommandWithCp56Time o) {
      return new RegulatingStepCommandWithCp56Time(o.address(), o.state(), o.qualifier(), time);
    }
    if (object instanceof SetpointNormalizedWithCp56Time o) {
      return new SetpointNormalizedWithCp56Time(o.address(), o.value(), o.qualifier(), time);
    }
    if (object instanceof SetpointScaledWithCp56Time o) {
      return new SetpointScaledWithCp56Time(o.address(), o.value(), o.qualifier(), time);
    }
    if (object instanceof SetpointShortFloatWithCp56Time o) {
      return new SetpointShortFloatWithCp56Time(o.address(), o.value(), o.qualifier(), time);
    }
    if (object instanceof Bitstring32CommandWithCp56Time o) {
      return new Bitstring32CommandWithCp56Time(o.address(), o.bits(), time);
    }
    return object;
  }
}
