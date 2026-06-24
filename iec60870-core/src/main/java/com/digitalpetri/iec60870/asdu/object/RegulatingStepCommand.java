package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.element.StepCommandState;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_RC_NA_1 (47) — regulating step command without time tag (IEC 60870-5-101 §7.3.2.3).
 *
 * <p>Carries a single regulating step command (RCO, §7.2.6.17) packed into one octet. The two-bit
 * regulating step command state (RCS) occupies the least significant bits and the qualifier of
 * command contributes the remaining bits: the five-bit {@code QU} field in bits 3..7 and the
 * select/execute flag ({@code S/E}) in bit 8.
 *
 * @param address the information object address.
 * @param state the regulating step command state (RCS).
 * @param qualifier the qualifier of command (QU + S/E).
 */
public record RegulatingStepCommand(
    InformationObjectAddress address, StepCommandState state, QualifierOfCommand qualifier)
    implements InformationObject {

  /** RCS — regulating step command state bits (bits 1..2). */
  private static final int RCS_MASK = 0x03;

  /**
   * Validates that the address, state, and qualifier are present.
   *
   * @param address the information object address.
   * @param state the regulating step command state (RCS).
   * @param qualifier the qualifier of command (QU + S/E).
   * @throws NullPointerException if {@code address}, {@code state}, or {@code qualifier} is null.
   */
  public RegulatingStepCommand {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link RegulatingStepCommand} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the RCO octet into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (one octet, bit 1 = least significant bit): RCS(b1..2), QU(b3..7), S/E(b8).
     * The state contributes bits 1..2 and the qualifier contributes bits 3..8 via {@link
     * QualifierOfCommand#toBits()}.
     *
     * @param o the regulating step command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(RegulatingStepCommand o, ByteBuf buffer) {
      int octet = (o.state().value() & RCS_MASK) | o.qualifier().toBits();
      buffer.writeByte(octet);
    }

    /**
     * Decodes the RCO octet from {@code buffer} (IOA already read); does not release the buffer.
     *
     * @param address the information object address read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded regulating step command.
     */
    public static RegulatingStepCommand decode(InformationObjectAddress address, ByteBuf buffer) {
      int octet = buffer.readUnsignedByte();
      StepCommandState state = StepCommandState.fromValue(octet & RCS_MASK);
      QualifierOfCommand qualifier = QualifierOfCommand.fromBits(octet);
      return new RegulatingStepCommand(address, state, qualifier);
    }
  }
}
