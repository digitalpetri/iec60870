package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_BO_TA_1 (8) — bitstring of 32 bit with a CP24Time2a time tag (IEC 60870-5-101 §7.3.1.8).
 *
 * <p>Carries a 32-bit binary state information value (BSI) together with its quality descriptor
 * (QDS) and a three-octet time tag. The 32 bits have no protocol-defined meaning; they are an
 * application-defined bitstring. Because each bitstring carries its own time tag, this type never
 * appears as a sequence of information elements (SQ = 0).
 *
 * @param address the information object address.
 * @param bits the 32-bit binary state information (BSI); treated as an opaque bitstring.
 * @param quality the quality descriptor (QDS).
 * @param time the CP24Time2a time tag of the event.
 */
public record Bitstring32WithCp24Time(
    InformationObjectAddress address, int bits, Qds quality, Cp24Time2a time)
    implements InformationObject {

  /**
   * Validates the components.
   *
   * @param address the information object address.
   * @param bits the 32-bit binary state information (BSI); treated as an opaque bitstring.
   * @param quality the quality descriptor (QDS).
   * @param time the CP24Time2a time tag of the event.
   * @throws IllegalArgumentException if {@code address}, {@code quality}, or {@code time} is {@code
   *     null}.
   */
  public Bitstring32WithCp24Time {
    if (address == null) {
      throw new IllegalArgumentException("address must not be null");
    }
    if (quality == null) {
      throw new IllegalArgumentException("quality must not be null");
    }
    if (time == null) {
      throw new IllegalArgumentException("time must not be null");
    }
  }

  /** Serde for the {@link Bitstring32WithCp24Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the BSI, QDS, and CP24Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first), written after the IOA:
     *
     * <ul>
     *   <li>octets 1-4: BSI — the 32-bit value little-endian;
     *   <li>octet 5: QDS — OV(b1), BL(b5), SB(b6), NT(b7), IV(b8);
     *   <li>octets 6-8: CP24Time2a — milliseconds (octets 1-2, LE) and minute/RES1/IV (octet 3).
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param o the object whose elements are encoded.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Bitstring32WithCp24Time o, ByteBuf buffer) {
      buffer.writeIntLE(o.bits());
      Qds.Serde.encode(o.quality(), buffer);
      Cp24Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the BSI, QDS, and CP24Time2a elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads a 4-octet little-endian BSI, a 1-octet QDS, then a 3-octet CP24Time2a. Does not
     * release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link Bitstring32WithCp24Time}.
     */
    public static Bitstring32WithCp24Time decode(InformationObjectAddress address, ByteBuf buffer) {
      int bits = buffer.readIntLE();
      Qds quality = Qds.Serde.decode(buffer);
      Cp24Time2a time = Cp24Time2a.Serde.decode(buffer);
      return new Bitstring32WithCp24Time(address, bits, quality, time);
    }
  }
}
