package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Sep;
import com.digitalpetri.iec60870.asdu.time.Cp16Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_EP_TA_1 (17) — event of protection equipment with a CP24Time2a time tag.
 *
 * <p>Reports a single protection-equipment event together with the elapsed (relay operating) time
 * and the time of the event. Because each event carries its own time tag, this type never appears
 * as a sequence of information elements (SQ = 0).
 *
 * @param address the information object address.
 * @param event the single event of protection equipment with quality bits (SEP).
 * @param elapsedTime the elapsed (relay operating) time (CP16Time2a).
 * @param time the CP24Time2a time tag of the event.
 */
public record EventOfProtectionEquipment(
    InformationObjectAddress address, Sep event, Cp16Time2a elapsedTime, Cp24Time2a time)
    implements InformationObject {

  /**
   * Validates that all required components are present.
   *
   * @param address the information object address.
   * @param event the single event of protection equipment with quality bits (SEP).
   * @param elapsedTime the elapsed (relay operating) time (CP16Time2a).
   * @param time the CP24Time2a time tag of the event.
   * @throws NullPointerException if {@code address}, {@code event}, {@code elapsedTime}, or {@code
   *     time} is {@code null}.
   */
  public EventOfProtectionEquipment {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(elapsedTime, "elapsedTime");
    Objects.requireNonNull(time, "time");
  }

  /** Serde for the {@link EventOfProtectionEquipment} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SEP, CP16Time2a, and CP24Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first), written after the IOA:
     *
     * <ul>
     *   <li>octet 1: SEP — ES(b1..2), EI(b4), BL(b5), SB(b6), NT(b7), IV(b8);
     *   <li>octets 2-3: CP16Time2a — elapsed time, milliseconds as an unsigned 16-bit integer (LE);
     *   <li>octets 4-6: CP24Time2a — milliseconds (octets 1-2, LE) and minute/RES1/IV (octet 3).
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param o the object whose elements are encoded.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(EventOfProtectionEquipment o, ByteBuf buffer) {
      Sep.Serde.encode(o.event(), buffer);
      Cp16Time2a.Serde.encode(o.elapsedTime(), buffer);
      Cp24Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the SEP, CP16Time2a, and CP24Time2a elements (IOA already read) from {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded event of protection equipment.
     */
    public static EventOfProtectionEquipment decode(
        InformationObjectAddress address, ByteBuf buffer) {
      Sep event = Sep.Serde.decode(buffer);
      Cp16Time2a elapsedTime = Cp16Time2a.Serde.decode(buffer);
      Cp24Time2a time = Cp24Time2a.Serde.decode(buffer);
      return new EventOfProtectionEquipment(address, event, elapsedTime, time);
    }
  }
}
