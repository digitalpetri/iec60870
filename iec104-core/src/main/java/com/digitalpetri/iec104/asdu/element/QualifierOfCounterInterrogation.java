package com.digitalpetri.iec104.asdu.element;

import com.digitalpetri.iec104.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * Qualifier of counter interrogation command (QCC) per IEC 60870-5-101 clause 7.2.6.23.
 *
 * <p>Encoded as a single octet: the request field ({@code RQT}) occupies bits 1..6 and the freeze
 * mode ({@code FRZ}) occupies bits 7..8. {@code RQT} value {@code 0} means no counter requested;
 * {@code 1..4} request counter groups {@code 1..4}; {@code 5} is a general counter request. The
 * freeze action is applied only to the selected counter group.
 *
 * @param request the {@code RQT} field, in the range {@code 0..63}.
 * @param freeze the freeze mode applied to the requested counter group.
 */
public record QualifierOfCounterInterrogation(int request, FreezeMode freeze) {

  /** Bit mask of the six-bit {@code RQT} field (bits 1..6). */
  private static final int REQUEST_MASK = 0x3F;

  /** Bit shift of the two-bit {@code FRZ} field (bits 7..8). */
  private static final int FREEZE_SHIFT = 6;

  /**
   * Validates the request range and freeze presence.
   *
   * @param request the {@code RQT} field, in the range {@code 0..63}.
   * @param freeze the freeze mode applied to the requested counter group.
   * @throws IllegalArgumentException if {@code request} is outside {@code 0..63}.
   * @throws NullPointerException if {@code freeze} is {@code null}.
   */
  public QualifierOfCounterInterrogation {
    if (request < 0 || request > REQUEST_MASK) {
      throw new IllegalArgumentException("request out of range [0, 63]: " + request);
    }
    Objects.requireNonNull(freeze, "freeze");
  }

  /** Serde for the {@link QualifierOfCounterInterrogation} octet (QCC, 7.2.6.23). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the QCC as a single octet into {@code buffer}: {@code RQT} in bits 1..6 and {@code
     * FRZ} in bits 7..8. Does not release the buffer.
     *
     * @param qcc the qualifier of counter interrogation to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(QualifierOfCounterInterrogation qcc, ByteBuf buffer) {
      int octet = (qcc.request() & REQUEST_MASK) | (qcc.freeze().value() << FREEZE_SHIFT);
      buffer.writeByte(octet);
    }

    /**
     * Decodes one QCC octet from {@code buffer}: {@code RQT} from bits 1..6 and {@code FRZ} from
     * bits 7..8. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link QualifierOfCounterInterrogation}.
     * @throws AsduDecodeException if the buffer does not contain at least one readable octet.
     */
    public static QualifierOfCounterInterrogation decode(ByteBuf buffer) {
      if (!buffer.isReadable()) {
        throw new AsduDecodeException("QualifierOfCounterInterrogation requires 1 octet");
      }
      int octet = buffer.readUnsignedByte();
      int request = octet & REQUEST_MASK;
      FreezeMode freeze = FreezeMode.fromValue((octet >> FREEZE_SHIFT) & 0x03);
      return new QualifierOfCounterInterrogation(request, freeze);
    }
  }
}
