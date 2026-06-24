package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import io.netty.buffer.ByteBuf;

/**
 * M_BO_NA_1 (7) — bitstring of 32 bit (IEC 60870-5-101 §7.3.1.7).
 *
 * <p>Carries a 32-bit binary state information value (BSI) together with its quality descriptor
 * (QDS). The 32 bits have no protocol-defined meaning; they are an application-defined bitstring.
 *
 * @param address the information object address.
 * @param bits the 32-bit binary state information (BSI); treated as an opaque bitstring.
 * @param quality the quality descriptor (QDS).
 */
public record Bitstring32(InformationObjectAddress address, int bits, Qds quality)
    implements InformationObject {

  /** Serde for the {@link Bitstring32} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the BSI and QDS into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (least significant octet first): BSI (4 octets, the 32-bit value
     * little-endian) followed by QDS (1 octet).
     *
     * @param o the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Bitstring32 o, ByteBuf buffer) {
      buffer.writeIntLE(o.bits());
      Qds.Serde.encode(o.quality(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads a 4-octet little-endian BSI followed by a 1-octet QDS.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link Bitstring32}.
     */
    public static Bitstring32 decode(InformationObjectAddress address, ByteBuf buffer) {
      int bits = buffer.readIntLE();
      Qds quality = Qds.Serde.decode(buffer);
      return new Bitstring32(address, bits, quality);
    }
  }
}
