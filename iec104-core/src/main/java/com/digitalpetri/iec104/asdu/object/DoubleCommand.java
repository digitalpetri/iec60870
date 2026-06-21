package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.DoubleCommandState;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_DC_NA_1 (46) — double command without time tag (IEC 60870-5-101 §7.3.2.2).
 *
 * <p>Carries a single double command state together with its qualifier of command, packed into one
 * DCO octet (§7.2.6.16). The double command state (DCS) occupies the two least significant bits;
 * the qualifier of command contributes the {@code QU} field in bits 3..7 and the select/execute
 * flag ({@code S/E}) in bit 8.
 *
 * @param address the information object address.
 * @param state the double command state (DCS).
 * @param qualifier the qualifier of command (QU and S/E).
 */
public record DoubleCommand(
    InformationObjectAddress address, DoubleCommandState state, QualifierOfCommand qualifier)
    implements InformationObject {

  /** DCS — double command state bits (bits 1..2). */
  private static final int DCS_MASK = 0x03;

  /**
   * Validates that the address, state, and qualifier are present.
   *
   * @param address the information object address.
   * @param state the double command state (DCS).
   * @param qualifier the qualifier of command (QU and S/E).
   * @throws NullPointerException if {@code address}, {@code state}, or {@code qualifier} is null.
   */
  public DoubleCommand {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link DoubleCommand} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the DCO octet into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (one octet, bit 1 = least significant bit): DCS(b1..2), QU(b3..7), S/E(b8).
     * The DCS bits come from {@link DoubleCommandState#value()} and the upper bits from {@link
     * QualifierOfCommand#toBits()}.
     *
     * @param o the double command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(DoubleCommand o, ByteBuf buffer) {
      int octet = (o.state().value() & DCS_MASK) | o.qualifier().toBits();
      buffer.writeByte(octet);
    }

    /**
     * Decodes the DCO octet from {@code buffer} (IOA already read); does not release the buffer.
     *
     * @param address the information object address read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded double command.
     */
    public static DoubleCommand decode(InformationObjectAddress address, ByteBuf buffer) {
      int octet = buffer.readUnsignedByte();
      DoubleCommandState state = DoubleCommandState.fromValue(octet & DCS_MASK);
      QualifierOfCommand qualifier = QualifierOfCommand.fromBits(octet);
      return new DoubleCommand(address, state, qualifier);
    }
  }
}
