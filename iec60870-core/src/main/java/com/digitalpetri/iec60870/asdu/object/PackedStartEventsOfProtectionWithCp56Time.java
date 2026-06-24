package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qdp;
import com.digitalpetri.iec60870.asdu.element.Spe;
import com.digitalpetri.iec60870.asdu.time.Cp16Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_EP_TE_1 (39) — packed start events of protection equipment with a CP56Time2a time tag.
 *
 * <p>Reports which start-of-operation events a protection device has detected, together with the
 * quality of those events, the relay duration time, and a seven-octet absolute time tag. Sent
 * spontaneously.
 *
 * @param address the information object address.
 * @param event the start events of protection equipment (SPE).
 * @param quality the quality descriptor for events of protection equipment (QDP).
 * @param elapsedTime the relay duration time (CP16Time2a).
 * @param time the CP56Time2a time tag.
 */
public record PackedStartEventsOfProtectionWithCp56Time(
    InformationObjectAddress address,
    Spe event,
    Qdp quality,
    Cp16Time2a elapsedTime,
    Cp56Time2a time)
    implements InformationObject {

  /**
   * Serde for the {@link PackedStartEventsOfProtectionWithCp56Time} information elements (after the
   * IOA).
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SPE, QDP, CP16Time2a, and CP56Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first): octet 1 = SPE, octet 2 = QDP, octets
     * 3..4 = CP16Time2a (relay duration time), octets 5..11 = CP56Time2a. The information object
     * address is not written here. Does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(PackedStartEventsOfProtectionWithCp56Time object, ByteBuf buffer) {
      Spe.Serde.encode(object.event(), buffer);
      Qdp.Serde.encode(object.quality(), buffer);
      Cp16Time2a.Serde.encode(object.elapsedTime(), buffer);
      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the SPE, QDP, CP16Time2a, and CP56Time2a elements (information object address already
     * read) from {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static PackedStartEventsOfProtectionWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      Spe event = Spe.Serde.decode(buffer);
      Qdp quality = Qdp.Serde.decode(buffer);
      Cp16Time2a elapsedTime = Cp16Time2a.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new PackedStartEventsOfProtectionWithCp56Time(
          address, event, quality, elapsedTime, time);
    }
  }
}
