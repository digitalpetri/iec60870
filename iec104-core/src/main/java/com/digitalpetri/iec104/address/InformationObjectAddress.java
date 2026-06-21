package com.digitalpetri.iec104.address;

import com.digitalpetri.iec104.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import org.joou.UInteger;

/**
 * The information object address, identifying a single information object within a station.
 *
 * <p>The value is an unsigned integer carried in one, two, or three octets, so its range is {@code
 * 0..16777215} ({@code 0x00FF_FFFF}). The value {@code 0} marks the address as irrelevant. The
 * number of octets actually carried on the wire is fixed per system by the {@link
 * com.digitalpetri.iec104.ProtocolProfile}; the address itself always holds the full unsigned
 * value.
 *
 * @param value the unsigned information object address value, at most {@code 0x00FF_FFFF}.
 */
public record InformationObjectAddress(UInteger value) {

  /** The largest valid information object address value, {@code 0x00FF_FFFF}. */
  public static final long MAX_VALUE = 0x00FF_FFFFL;

  /**
   * Validates the information object address.
   *
   * @param value the unsigned information object address value, at most {@code 0x00FF_FFFF}.
   * @throws NullPointerException if {@code value} is null.
   * @throws IllegalArgumentException if {@code value} exceeds {@code 0x00FF_FFFF}.
   */
  public InformationObjectAddress {
    Objects.requireNonNull(value, "value");
    if (value.longValue() > MAX_VALUE) {
      throw new IllegalArgumentException(
          "information object address must be <= 0x00FF_FFFF: " + value.longValue());
    }
  }

  /**
   * Creates an information object address from a {@code long} value.
   *
   * @param value the information object address value, in the range {@code 0..16777215}.
   * @return the information object address.
   * @throws IllegalArgumentException if {@code value} is negative or exceeds {@code 0x00FF_FFFF}.
   */
  public static InformationObjectAddress of(long value) {
    if (value < 0 || value > MAX_VALUE) {
      throw new IllegalArgumentException(
          "information object address must be in 0..16777215: " + value);
    }
    return new InformationObjectAddress(UInteger.valueOf(value));
  }

  /**
   * Serializer/deserializer for the information object address.
   *
   * <p>The value is written and read least-significant octet first (little-endian) over a width of
   * {@code length} octets. {@link #encode} writes into a caller-owned buffer and never releases it;
   * {@link #decode} reads from a caller-owned buffer and never releases it.
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the information object address as {@code length} little-endian octets into {@code
     * buffer}.
     *
     * @param address the information object address to encode.
     * @param length the number of octets to write, in the range {@code 1..3}.
     * @param buffer the buffer to write into; not released by this method.
     * @throws IllegalArgumentException if {@code length} is not in {@code 1..3}, or if the value
     *     does not fit in {@code length} octets.
     */
    public static void encode(InformationObjectAddress address, int length, ByteBuf buffer) {
      long value = address.value().longValue();
      long max = (1L << (8 * length)) - 1;
      if (length < 1 || length > 3) {
        throw new IllegalArgumentException(
            "information object address length must be 1..3: " + length);
      }
      if (value > max) {
        throw new IllegalArgumentException(
            "information object address does not fit in " + length + " octet(s): " + value);
      }
      for (int i = 0; i < length; i++) {
        buffer.writeByte((int) ((value >> (8 * i)) & 0xFF));
      }
    }

    /**
     * Decodes an information object address from {@code length} little-endian octets read from
     * {@code buffer}.
     *
     * @param length the number of octets to read, in the range {@code 1..3}.
     * @param buffer the buffer to read from; not released by this method.
     * @return the decoded information object address.
     * @throws IllegalArgumentException if {@code length} is not in {@code 1..3}.
     * @throws AsduDecodeException if fewer than {@code length} octets are readable.
     */
    public static InformationObjectAddress decode(int length, ByteBuf buffer) {
      if (length < 1 || length > 3) {
        throw new IllegalArgumentException(
            "information object address length must be 1..3: " + length);
      }
      if (buffer.readableBytes() < length) {
        throw new AsduDecodeException(
            "information object address requires "
                + length
                + " octets, have "
                + buffer.readableBytes());
      }
      long value = 0;
      for (int i = 0; i < length; i++) {
        value |= ((long) buffer.readUnsignedByte()) << (8 * i);
      }
      return new InformationObjectAddress(UInteger.valueOf(value));
    }
  }
}
