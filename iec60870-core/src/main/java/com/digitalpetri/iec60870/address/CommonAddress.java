package com.digitalpetri.iec60870.address;

import com.digitalpetri.iec60870.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import org.joou.UShort;

/**
 * The common address of an ASDU, identifying the station the ASDU's information objects belong to.
 *
 * <p>The value is an unsigned 16-bit integer. The value {@code 0} is unused, {@code 65535} ({@code
 * 0xFFFF}) is the global (broadcast) address, and all other values are station addresses. The
 * number of octets actually carried on the wire (one or two) is fixed per system by the {@link
 * com.digitalpetri.iec60870.ProtocolProfile}; the address itself always holds the full unsigned
 * value.
 *
 * @param value the unsigned 16-bit common address value.
 */
public record CommonAddress(UShort value) {

  /**
   * Validates the common address.
   *
   * @param value the unsigned 16-bit common address value.
   * @throws NullPointerException if {@code value} is null.
   */
  public CommonAddress {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Creates a common address from an {@code int} value.
   *
   * @param value the common address value, in the range {@code 0..65535}.
   * @return the common address.
   * @throws IllegalArgumentException if {@code value} is not in the range {@code 0..65535}.
   */
  public static CommonAddress of(int value) {
    if (value < 0 || value > 65535) {
      throw new IllegalArgumentException("common address must be in 0..65535: " + value);
    }
    return new CommonAddress(UShort.valueOf(value));
  }

  /**
   * Serializer/deserializer for the common address.
   *
   * <p>The value is written and read least-significant octet first (little-endian) over a width of
   * {@code length} octets. {@link #encode} writes into a caller-owned buffer and never releases it;
   * {@link #decode} reads from a caller-owned buffer and never releases it.
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the common address as {@code length} little-endian octets into {@code buffer}.
     *
     * @param address the common address to encode.
     * @param length the number of octets to write, either {@code 1} or {@code 2}.
     * @param buffer the buffer to write into; not released by this method.
     * @throws IllegalArgumentException if {@code length} is not {@code 1} or {@code 2}, or if the
     *     value does not fit in {@code length} octets.
     */
    public static void encode(CommonAddress address, int length, ByteBuf buffer) {
      int value = address.value().intValue();
      switch (length) {
        case 1 -> {
          if (value > 0xFF) {
            throw new IllegalArgumentException("common address does not fit in 1 octet: " + value);
          }
          buffer.writeByte(value);
        }
        case 2 -> buffer.writeShortLE(value);
        default ->
            throw new IllegalArgumentException("common address length must be 1 or 2: " + length);
      }
    }

    /**
     * Decodes a common address from {@code length} little-endian octets read from {@code buffer}.
     *
     * @param length the number of octets to read, either {@code 1} or {@code 2}.
     * @param buffer the buffer to read from; not released by this method.
     * @return the decoded common address.
     * @throws IllegalArgumentException if {@code length} is not {@code 1} or {@code 2}.
     * @throws AsduDecodeException if fewer than {@code length} octets are readable.
     */
    public static CommonAddress decode(int length, ByteBuf buffer) {
      if (length != 1 && length != 2) {
        throw new IllegalArgumentException("common address length must be 1 or 2: " + length);
      }
      if (buffer.readableBytes() < length) {
        throw new AsduDecodeException(
            "common address requires " + length + " octets, have " + buffer.readableBytes());
      }
      int value = (length == 1) ? buffer.readUnsignedByte() : buffer.readUnsignedShortLE();
      return new CommonAddress(UShort.valueOf(value));
    }
  }
}
