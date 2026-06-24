package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec104.asdu.element.StepCommandState;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_RC_TA_1 (60) — regulating step command with a CP56Time2a time tag.
 *
 * <p>Issues a regulating step command (raise/lower) toward a controlled point, qualified by a
 * {@link QualifierOfCommand} and carrying a full seven-octet binary time tag. The command state
 * (RCS) and the qualifier of command (QOC) share a single octet (RCO); the time tag follows.
 * Because the object carries an individual time tag it never appears as a sequence of information
 * elements (SQ = 1).
 *
 * @param address the information object address.
 * @param state the regulating step command state (RCS) in bits 1..2 of the RCO octet.
 * @param qualifier the qualifier of command (QOC) contributing the QU field (bits 3..7) and the
 *     select/execute flag (bit 8) of the RCO octet.
 * @param time the CP56Time2a time tag.
 */
public record RegulatingStepCommandWithCp56Time(
    InformationObjectAddress address,
    StepCommandState state,
    QualifierOfCommand qualifier,
    Cp56Time2a time)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param state the regulating step command state (RCS).
   * @param qualifier the qualifier of command (QOC).
   * @param time the CP56Time2a time tag.
   * @throws NullPointerException if {@code address}, {@code state}, {@code qualifier}, or {@code
   *     time} is null.
   */
  public RegulatingStepCommandWithCp56Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(qualifier, "qualifier");
    Objects.requireNonNull(time, "time");
  }

  /**
   * Serde for the {@link RegulatingStepCommandWithCp56Time} information elements (after the IOA).
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the RCO octet followed by the CP56Time2a time tag into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first):
     *
     * <ul>
     *   <li>octet 1: RCO — RCS(b1..2) = {@code state}, QU(b3..7) and S/E(b8) supplied by {@code
     *       qualifier}; the RCS field and the qualifier bits are OR-combined into the single octet.
     *   <li>octets 2..8: CP56Time2a, seven octets little-endian.
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(RegulatingStepCommandWithCp56Time object, ByteBuf buffer) {
      int octet = (object.state().value() & 0x03) | object.qualifier().toBits();
      buffer.writeByte(octet);

      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the RCO octet and the CP56Time2a time tag from {@code buffer}, with the IOA already
     * read by the caller.
     *
     * <p>Bits 1..2 of the RCO octet yield the {@link StepCommandState}; bits 3..8 yield the {@link
     * QualifierOfCommand}. Does not release the buffer.
     *
     * @param address the information object address read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static RegulatingStepCommandWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      int octet = buffer.readUnsignedByte();
      StepCommandState state = StepCommandState.fromValue(octet & 0x03);
      QualifierOfCommand qualifier = QualifierOfCommand.fromBits(octet);

      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);

      return new RegulatingStepCommandWithCp56Time(address, state, qualifier, time);
    }
  }
}
