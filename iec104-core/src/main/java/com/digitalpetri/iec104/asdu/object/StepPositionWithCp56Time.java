package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.element.Vti;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_ST_TB_1 (32) — step position information with a CP56Time2a time tag.
 *
 * <p>Carries a transformer tap position (or other step position) as a signed value with transient
 * state indication, a quality descriptor, and a seven-octet absolute time tag. Used spontaneously
 * or as return information for remote and local step commands.
 *
 * @param address the information object address.
 * @param value the step position value with transient state indication (VTI).
 * @param quality the quality descriptor (QDS).
 * @param time the CP56Time2a time tag.
 */
public record StepPositionWithCp56Time(
    InformationObjectAddress address, Vti value, Qds quality, Cp56Time2a time)
    implements InformationObject {

  /** Serde for the {@link StepPositionWithCp56Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the VTI, QDS, and CP56Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first): octet 1 = VTI (signed 7-bit value
     * plus transient-state bit), octet 2 = QDS, octets 3..9 = CP56Time2a. The information object
     * address is not written here. Does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(StepPositionWithCp56Time object, ByteBuf buffer) {
      Vti.Serde.encode(object.value(), buffer);
      Qds.Serde.encode(object.quality(), buffer);
      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the VTI, QDS, and CP56Time2a elements (information object address already read) from
     * {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static StepPositionWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      Vti value = Vti.Serde.decode(buffer);
      Qds quality = Qds.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new StepPositionWithCp56Time(address, value, quality, time);
    }
  }
}
