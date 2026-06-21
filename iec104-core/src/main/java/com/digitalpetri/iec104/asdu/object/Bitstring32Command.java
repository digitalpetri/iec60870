package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_BO_NA_1 (51) — bitstring of 32 bit command (IEC 60870-5-101 §7.3.2.7).
 *
 * <p>Sends a 32-bit binary state information value (BSI) in the control direction. The 32 bits have
 * no protocol-defined meaning; they are an application-defined bitstring. Unlike the monitor-
 * direction {@link Bitstring32} (M_BO_NA_1), this command carries no quality descriptor.
 *
 * @param address the information object address.
 * @param bits the 32-bit binary state information (BSI); treated as an opaque bitstring.
 */
public record Bitstring32Command(InformationObjectAddress address, int bits)
    implements InformationObject {

  /**
   * Validates the components.
   *
   * @param address the information object address.
   * @param bits the 32-bit binary state information (BSI); treated as an opaque bitstring.
   * @throws NullPointerException if {@code address} is null.
   */
  public Bitstring32Command {
    Objects.requireNonNull(address, "address");
  }

  /** Serde for the {@link Bitstring32Command} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the BSI into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (least significant octet first): BSI (4 octets, the 32-bit value
     * little-endian). No quality octet follows.
     *
     * @param o the command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Bitstring32Command o, ByteBuf buffer) {
      buffer.writeIntLE(o.bits());
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads a 4-octet little-endian BSI. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link Bitstring32Command}.
     */
    public static Bitstring32Command decode(InformationObjectAddress address, ByteBuf buffer) {
      int bits = buffer.readIntLE();
      return new Bitstring32Command(address, bits);
    }
  }
}
