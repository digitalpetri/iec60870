package com.digitalpetri.iec60870.asdu.element;

import com.digitalpetri.iec60870.AsduDecodeException;
import io.netty.buffer.ByteBuf;

/**
 * Cause of initialization (COI) per IEC 60870-5-101 clause 7.2.6.21.
 *
 * <p>Encoded as a single octet: the cause value ({@code UI7}) occupies bits 1..7 and the
 * after-parameter-change flag ({@code BS1}) occupies bit 8. Standard cause values are {@code 0}
 * local power switch on, {@code 1} local manual reset, and {@code 2} remote reset; higher values
 * are reserved or private. The flag distinguishes initialization with unchanged local parameters
 * ({@code false}) from initialization after a change of local parameters ({@code true}).
 *
 * @param value the {@code UI7} cause value, in the range {@code 0..127}.
 * @param afterParameterChange {@code true} if initialization followed a change of local parameters
 *     ({@code BS1 = 1}); {@code false} for unchanged local parameters ({@code BS1 = 0}).
 */
public record CauseOfInitialization(int value, boolean afterParameterChange) {

  /** Bit mask of the seven-bit cause value (bits 1..7). */
  private static final int VALUE_MASK = 0x7F;

  /** Bit mask of the after-parameter-change flag (bit 8). */
  private static final int CHANGE_MASK = 0x80;

  /**
   * Validates the components.
   *
   * @param value the {@code UI7} cause value, in the range {@code 0..127}.
   * @param afterParameterChange {@code true} if initialization followed a change of local
   *     parameters ({@code BS1 = 1}); {@code false} for unchanged local parameters ({@code BS1 =
   *     0}).
   * @throws IllegalArgumentException if {@code value} is outside {@code 0..127}.
   */
  public CauseOfInitialization {
    if (value < 0 || value > VALUE_MASK) {
      throw new IllegalArgumentException("value out of range [0, 127]: " + value);
    }
  }

  /** Serde for the {@link CauseOfInitialization} octet (COI, 7.2.6.21). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the COI as a single octet into {@code buffer}: cause value in bits 1..7 and the
     * after-parameter-change flag in bit 8. Does not release the buffer.
     *
     * @param coi the cause of initialization to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(CauseOfInitialization coi, ByteBuf buffer) {
      int octet = (coi.value() & VALUE_MASK) | (coi.afterParameterChange() ? CHANGE_MASK : 0);
      buffer.writeByte(octet);
    }

    /**
     * Decodes one COI octet from {@code buffer}: cause value from bits 1..7 and the
     * after-parameter-change flag from bit 8. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link CauseOfInitialization}.
     * @throws AsduDecodeException if the buffer does not contain at least one readable octet.
     */
    public static CauseOfInitialization decode(ByteBuf buffer) {
      if (!buffer.isReadable()) {
        throw new AsduDecodeException("CauseOfInitialization requires 1 octet");
      }
      int octet = buffer.readUnsignedByte();
      int value = octet & VALUE_MASK;
      boolean afterParameterChange = (octet & CHANGE_MASK) != 0;
      return new CauseOfInitialization(value, afterParameterChange);
    }
  }
}
