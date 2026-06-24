package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Sep;
import com.digitalpetri.iec60870.asdu.time.Cp16Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_EP_TD_1 (38) — event of protection equipment with a CP56Time2a time tag.
 *
 * <p>Reports a single protection-equipment event together with the relay operating (elapsed) time
 * and the absolute time of the event. Because each event carries its own time tag, this type is
 * always transmitted as a sequence of information objects (SQ = 0) and is emitted spontaneously.
 *
 * @param address the information object address.
 * @param event the single event of protection equipment with quality bits (SEP).
 * @param elapsedTime the elapsed (relay operating) time as a two-octet duration (CP16Time2a).
 * @param time the CP56Time2a time tag of the event.
 */
public record EventOfProtectionEquipmentWithCp56Time(
    InformationObjectAddress address, Sep event, Cp16Time2a elapsedTime, Cp56Time2a time)
    implements InformationObject {

  /** Serde for the {@link EventOfProtectionEquipmentWithCp56Time} information elements. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SEP, CP16Time2a, and CP56Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first): octet 1 = SEP, octets 2..3 =
     * CP16Time2a elapsed time, octets 4..10 = CP56Time2a time tag. The information object address
     * is not written here. Does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(EventOfProtectionEquipmentWithCp56Time object, ByteBuf buffer) {
      Sep.Serde.encode(object.event(), buffer);
      Cp16Time2a.Serde.encode(object.elapsedTime(), buffer);
      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the SEP, CP16Time2a, and CP56Time2a elements (information object address already
     * read) from {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static EventOfProtectionEquipmentWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      Sep event = Sep.Serde.decode(buffer);
      Cp16Time2a elapsedTime = Cp16Time2a.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new EventOfProtectionEquipmentWithCp56Time(address, event, elapsedTime, time);
    }
  }
}
