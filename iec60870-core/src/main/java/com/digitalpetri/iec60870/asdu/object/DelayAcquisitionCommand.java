package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.time.Cp16Time2a;
import io.netty.buffer.ByteBuf;

/**
 * C_CD_NA_1 (106) — delay acquisition command (IEC 60870-5-101 §7.3.4.7).
 *
 * <p>In the control direction this command communicates an acquisition delay, expressed as a
 * CP16Time2a duration, used to synchronize the timing of cyclic data acquisition (cause of
 * transmission {@code activation} or {@code spontaneous}). In the monitor direction the same type
 * confirms activation.
 *
 * <p>The information object always appears individually (SQ = 0); it carries a single two-octet
 * time element and no quality descriptor.
 *
 * @param address the information object address; this type conventionally uses address {@code 0}.
 * @param delay the acquisition delay as a CP16Time2a duration in milliseconds.
 */
public record DelayAcquisitionCommand(InformationObjectAddress address, Cp16Time2a delay)
    implements InformationObject {

  /**
   * Serde for the {@link DelayAcquisitionCommand} information elements (after the IOA).
   *
   * <p>Wire layout (Mode 1, least significant octet first), two octets total:
   *
   * <ul>
   *   <li>octets 1-2: CP16Time2a delay, as encoded by {@link Cp16Time2a.Serde}.
   * </ul>
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the CP16Time2a element into {@code buffer}; does not write the IOA or release the
     * buffer.
     *
     * @param o the object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(DelayAcquisitionCommand o, ByteBuf buffer) {
      Cp16Time2a.Serde.encode(o.delay(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer positioned at the first CP16Time2a octet.
     * @return the decoded object.
     */
    public static DelayAcquisitionCommand decode(InformationObjectAddress address, ByteBuf buffer) {
      Cp16Time2a delay = Cp16Time2a.Serde.decode(buffer);
      return new DelayAcquisitionCommand(address, delay);
    }
  }
}
