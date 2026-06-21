package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_BO_TB_1 (33) — bitstring of 32 bit with time tag CP56Time2a (IEC 60870-5-101 §7.3.1.25).
 *
 * <p>Carries a 32-bit binary state information value (BSI) together with its quality descriptor
 * (QDS) and a seven-octet CP56Time2a time tag. The 32 bits have no protocol-defined meaning; they
 * are an application-defined bitstring.
 *
 * @param address the information object address.
 * @param bits the 32-bit binary state information (BSI); treated as an opaque bitstring.
 * @param quality the quality descriptor (QDS).
 * @param time the CP56Time2a time tag.
 */
public record Bitstring32WithCp56Time(
    InformationObjectAddress address, int bits, Qds quality, Cp56Time2a time)
    implements InformationObject {

  /** Serde for the {@link Bitstring32WithCp56Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the BSI, QDS, and CP56Time2a into {@code buffer}; does not write the IOA or release
     * the buffer.
     *
     * <p>Wire layout (Mode 1, least significant octet first): BSI (4 octets, the 32-bit value
     * little-endian) followed by QDS (1 octet) and CP56Time2a (7 octets).
     *
     * @param o the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Bitstring32WithCp56Time o, ByteBuf buffer) {
      buffer.writeIntLE(o.bits());
      Qds.Serde.encode(o.quality(), buffer);
      Cp56Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads a 4-octet little-endian BSI followed by a 1-octet QDS and a 7-octet CP56Time2a.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link Bitstring32WithCp56Time}.
     */
    public static Bitstring32WithCp56Time decode(InformationObjectAddress address, ByteBuf buffer) {
      int bits = buffer.readIntLE();
      Qds quality = Qds.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new Bitstring32WithCp56Time(address, bits, quality, time);
    }
  }
}
