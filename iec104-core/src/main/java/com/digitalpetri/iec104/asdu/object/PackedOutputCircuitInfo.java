package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Oci;
import com.digitalpetri.iec104.asdu.element.Qdp;
import com.digitalpetri.iec104.asdu.time.Cp16Time2a;
import com.digitalpetri.iec104.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_EP_TC_1 (19) — packed output circuit information of protection equipment with time tag.
 *
 * <p>Reports which output circuits of protection equipment have been commanded, together with a
 * quality descriptor, the relay operating time, and a three-octet time tag of the event. Because
 * each object carries its own time tag, this type never appears as a sequence of information
 * elements (SQ = 0).
 *
 * @param address the information object address.
 * @param event the output-circuit information of the protection equipment (OCI).
 * @param quality the quality descriptor of the protection-equipment event (QDP).
 * @param elapsedTime the relay operating time (CP16Time2a).
 * @param time the CP24Time2a time tag of the event.
 */
public record PackedOutputCircuitInfo(
    InformationObjectAddress address,
    Oci event,
    Qdp quality,
    Cp16Time2a elapsedTime,
    Cp24Time2a time)
    implements InformationObject {

  /**
   * Validates that all required components are present.
   *
   * @param address the information object address.
   * @param event the output-circuit information of the protection equipment (OCI).
   * @param quality the quality descriptor of the protection-equipment event (QDP).
   * @param elapsedTime the relay operating time (CP16Time2a).
   * @param time the CP24Time2a time tag of the event.
   * @throws NullPointerException if {@code address}, {@code event}, {@code quality}, {@code
   *     elapsedTime}, or {@code time} is {@code null}.
   */
  public PackedOutputCircuitInfo {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(quality, "quality");
    Objects.requireNonNull(elapsedTime, "elapsedTime");
    Objects.requireNonNull(time, "time");
  }

  /** Serde for the {@link PackedOutputCircuitInfo} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the OCI, QDP, CP16Time2a, and CP24Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first), written after the IOA:
     *
     * <ul>
     *   <li>octet 1: OCI — GC(b1), CL1(b2), CL2(b3), CL3(b4);
     *   <li>octet 2: QDP — EI(b4), BL(b5), SB(b6), NT(b7), IV(b8);
     *   <li>octets 3-4: CP16Time2a — relay operating time, milliseconds (LE);
     *   <li>octets 5-7: CP24Time2a — milliseconds (octets 1-2, LE) and minute/RES1/IV (octet 3).
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param o the object whose elements are encoded.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(PackedOutputCircuitInfo o, ByteBuf buffer) {
      Oci.Serde.encode(o.event(), buffer);
      Qdp.Serde.encode(o.quality(), buffer);
      Cp16Time2a.Serde.encode(o.elapsedTime(), buffer);
      Cp24Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the OCI, QDP, CP16Time2a, and CP24Time2a elements (IOA already read) from {@code
     * buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded packed output circuit information.
     */
    public static PackedOutputCircuitInfo decode(InformationObjectAddress address, ByteBuf buffer) {
      Oci event = Oci.Serde.decode(buffer);
      Qdp quality = Qdp.Serde.decode(buffer);
      Cp16Time2a elapsedTime = Cp16Time2a.Serde.decode(buffer);
      Cp24Time2a time = Cp24Time2a.Serde.decode(buffer);
      return new PackedOutputCircuitInfo(address, event, quality, elapsedTime, time);
    }
  }
}
