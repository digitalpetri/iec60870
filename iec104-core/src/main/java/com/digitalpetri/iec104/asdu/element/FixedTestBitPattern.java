package com.digitalpetri.iec104.asdu.element;

import static org.joou.Unsigned.ushort;

import com.digitalpetri.iec104.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import org.joou.UShort;

/**
 * Fixed test bit pattern (FBP) per IEC 60870-5-101 clause 7.2.6.14.
 *
 * <p>A two-octet pattern echoed in test commands to verify the data path. The standard default
 * pattern is {@code 0x55AA}, available as {@link #DEFAULT}.
 *
 * @param value the test bit pattern, in the range {@code 0..65535}.
 */
public record FixedTestBitPattern(UShort value) {

  /** The standard default test bit pattern {@code 0x55AA}. */
  public static final FixedTestBitPattern DEFAULT = of(0x55AA);

  /**
   * Creates a fixed test bit pattern from a raw 16-bit value.
   *
   * @param value the test bit pattern, in the range {@code 0..65535}.
   * @return the {@link FixedTestBitPattern} carrying {@code value}.
   * @throws IllegalArgumentException if {@code value} is outside {@code 0..65535}.
   */
  public static FixedTestBitPattern of(int value) {
    if (value < 0 || value > 0xFFFF) {
      throw new IllegalArgumentException("value out of range [0, 65535]: " + value);
    }
    return new FixedTestBitPattern(ushort(value));
  }

  /** Serde for the {@link FixedTestBitPattern} two octets (FBP, 7.2.6.14). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the FBP as two octets, least significant octet first, into {@code buffer}. Does not
     * release the buffer.
     *
     * @param fbp the fixed test bit pattern to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(FixedTestBitPattern fbp, ByteBuf buffer) {
      buffer.writeShortLE(fbp.value().intValue());
    }

    /**
     * Decodes two octets from {@code buffer}, least significant octet first. Does not release the
     * buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link FixedTestBitPattern}.
     * @throws AsduDecodeException if the buffer does not contain at least two readable octets.
     */
    public static FixedTestBitPattern decode(ByteBuf buffer) {
      if (buffer.readableBytes() < 2) {
        throw new AsduDecodeException("FixedTestBitPattern requires 2 octets");
      }
      return of(buffer.readUnsignedShortLE());
    }
  }
}
