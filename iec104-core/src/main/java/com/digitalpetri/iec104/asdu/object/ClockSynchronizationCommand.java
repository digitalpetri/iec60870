package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * C_CS_NA_1 (103) — clock synchronization command (IEC 60870-5-101 §7.3.4.4).
 *
 * <p>In the control direction this command sets the clock of an outstation to the supplied
 * CP56Time2a value (cause of transmission {@code activation}). In the monitor direction the same
 * type is used to confirm activation and, optionally, for spontaneous transmission of the clock
 * time (for example to mark the change of hour at an outstation).
 *
 * <p>The information object always appears individually (SQ = 0); it carries a single seven-octet
 * time element and no quality descriptor.
 *
 * @param address the information object address; this type conventionally uses address {@code 0}.
 * @param time the CP56Time2a clock value to synchronize to.
 */
public record ClockSynchronizationCommand(InformationObjectAddress address, Cp56Time2a time)
    implements InformationObject {

  /**
   * Serde for the {@link ClockSynchronizationCommand} information elements (after the IOA).
   *
   * <p>Wire layout (Mode 1, least significant octet first), seven octets total:
   *
   * <ul>
   *   <li>octets 1-7: CP56Time2a, as encoded by {@link Cp56Time2a.Serde}.
   * </ul>
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the CP56Time2a element into {@code buffer}; does not write the IOA or release the
     * buffer.
     *
     * @param o the object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(ClockSynchronizationCommand o, ByteBuf buffer) {
      Cp56Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer positioned at the first CP56Time2a octet.
     * @return the decoded object.
     */
    public static ClockSynchronizationCommand decode(
        InformationObjectAddress address, ByteBuf buffer) {
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new ClockSynchronizationCommand(address, time);
    }
  }
}
