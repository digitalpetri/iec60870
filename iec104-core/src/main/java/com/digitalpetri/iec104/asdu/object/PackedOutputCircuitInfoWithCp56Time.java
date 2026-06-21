package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Oci;
import com.digitalpetri.iec104.asdu.element.Qdp;
import com.digitalpetri.iec104.asdu.time.Cp16Time2a;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_EP_TF_1 (40) — packed output circuit information of protection equipment with a CP56Time2a time
 * tag.
 *
 * <p>Reports which output circuits of the protection equipment were commanded, together with the
 * relay operating (elapsed) time and the absolute time of the event. Because each event carries its
 * own time tag, this type is always transmitted as a sequence of information objects (SQ = 0) and
 * is emitted spontaneously.
 *
 * @param address the information object address.
 * @param event the output-circuit information of the protection equipment (OCI).
 * @param quality the quality descriptor of the protection-equipment event (QDP).
 * @param elapsedTime the elapsed (relay operating) time as a two-octet duration (CP16Time2a).
 * @param time the CP56Time2a time tag of the event.
 */
public record PackedOutputCircuitInfoWithCp56Time(
    InformationObjectAddress address,
    Oci event,
    Qdp quality,
    Cp16Time2a elapsedTime,
    Cp56Time2a time)
    implements InformationObject {

  /** Serde for the {@link PackedOutputCircuitInfoWithCp56Time} information elements. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the OCI, QDP, CP16Time2a, and CP56Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first): octet 1 = OCI, octet 2 = QDP, octets
     * 3..4 = CP16Time2a elapsed time, octets 5..11 = CP56Time2a time tag. The information object
     * address is not written here. Does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(PackedOutputCircuitInfoWithCp56Time object, ByteBuf buffer) {
      Oci.Serde.encode(object.event(), buffer);
      Qdp.Serde.encode(object.quality(), buffer);
      Cp16Time2a.Serde.encode(object.elapsedTime(), buffer);
      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the OCI, QDP, CP16Time2a, and CP56Time2a elements (information object address already
     * read) from {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static PackedOutputCircuitInfoWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      Oci event = Oci.Serde.decode(buffer);
      Qdp quality = Qdp.Serde.decode(buffer);
      Cp16Time2a elapsedTime = Cp16Time2a.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new PackedOutputCircuitInfoWithCp56Time(address, event, quality, elapsedTime, time);
    }
  }
}
