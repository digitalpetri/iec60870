package com.digitalpetri.iec60870.server;

import com.digitalpetri.iec60870.asdu.InformationObject;
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
import org.jspecify.annotations.Nullable;

/**
 * Derives the {@link CommandMode} (select or execute) of a received control command from its
 * select/ execute flag.
 *
 * <p>Single, double, and regulating-step commands carry the flag in their qualifier of command; the
 * set-point commands carry it in their qualifier of set-point. Bit-string commands ({@code C_BO})
 * have no select/execute flag and are always treated as {@link CommandMode#EXECUTE}.
 */
final class CommandModes {

  private CommandModes() {}

  /**
   * Returns the select/execute mode of a received command information object.
   *
   * @param object the received command information object.
   * @return the command mode.
   */
  static CommandMode of(InformationObject object) {
    Boolean select = selectFlag(object);
    if (select == null) {
      // C_BO commands (and anything without an S/E flag) are direct execute.
      return CommandMode.EXECUTE;
    }
    return select ? CommandMode.SELECT : CommandMode.EXECUTE;
  }

  /**
   * Returns the select/execute flag of a command object, or {@code null} if the object has none.
   *
   * @param object the command information object.
   * @return {@code true} for select, {@code false} for execute, or {@code null} if not applicable.
   */
  private static @Nullable Boolean selectFlag(InformationObject object) {
    if (object instanceof SingleCommand o) {
      return o.qualifier().select();
    }
    if (object instanceof SingleCommandWithCp56Time o) {
      return o.qualifier().select();
    }
    if (object instanceof DoubleCommand o) {
      return o.qualifier().select();
    }
    if (object instanceof DoubleCommandWithCp56Time o) {
      return o.qualifier().select();
    }
    if (object instanceof RegulatingStepCommand o) {
      return o.qualifier().select();
    }
    if (object instanceof RegulatingStepCommandWithCp56Time o) {
      return o.qualifier().select();
    }
    if (object instanceof SetpointNormalized o) {
      return o.qualifier().select();
    }
    if (object instanceof SetpointNormalizedWithCp56Time o) {
      return o.qualifier().select();
    }
    if (object instanceof SetpointScaled o) {
      return o.qualifier().select();
    }
    if (object instanceof SetpointScaledWithCp56Time o) {
      return o.qualifier().select();
    }
    if (object instanceof SetpointShortFloat o) {
      return o.qualifier().select();
    }
    if (object instanceof SetpointShortFloatWithCp56Time o) {
      return o.qualifier().select();
    }
    return null;
  }
}
