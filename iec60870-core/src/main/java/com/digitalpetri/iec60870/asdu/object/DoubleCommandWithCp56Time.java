package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.DoubleCommandState;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_DC_TA_1 (59) — double command with a CP56Time2a time tag.
 *
 * <p>Issues a double command (DCO) together with a full seven-octet binary time tag. The command
 * state and the qualifier of command share a single octet on the wire, with the time tag following.
 * Because each object carries an individual time tag, this type never appears as a sequence of
 * information elements (SQ = 1).
 *
 * @param address the information object address.
 * @param state the double command state (DCS).
 * @param qualifier the qualifier of command (QOC): the {@code QU} field and the select/execute
 *     flag, which share the command octet with {@code state}.
 * @param time the CP56Time2a time tag.
 */
public record DoubleCommandWithCp56Time(
    InformationObjectAddress address,
    DoubleCommandState state,
    QualifierOfCommand qualifier,
    Cp56Time2a time)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param state the double command state (DCS).
   * @param qualifier the qualifier of command (QOC).
   * @param time the CP56Time2a time tag.
   * @throws NullPointerException if {@code address}, {@code state}, {@code qualifier}, or {@code
   *     time} is null.
   */
  public DoubleCommandWithCp56Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(qualifier, "qualifier");
    Objects.requireNonNull(time, "time");
  }

  /** Serde for the {@link DoubleCommandWithCp56Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the DCO octet followed by the CP56Time2a time tag into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first):
     *
     * <ul>
     *   <li>octet 1: DCO — DCS(b1..2) = {@code state}, QU(b3..7) and S/E(b8) from {@code
     *       qualifier}.
     *   <li>octets 2..8: CP56Time2a, seven octets little-endian.
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(DoubleCommandWithCp56Time object, ByteBuf buffer) {
      int octet = (object.state().value() & 0x03) | object.qualifier().toBits();
      buffer.writeByte(octet);

      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the DCO octet and the CP56Time2a time tag from {@code buffer}, with the IOA already
     * read by the caller.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static DoubleCommandWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      int octet = buffer.readUnsignedByte();
      DoubleCommandState state = DoubleCommandState.fromValue(octet & 0x03);
      QualifierOfCommand qualifier = QualifierOfCommand.fromBits(octet);

      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);

      return new DoubleCommandWithCp56Time(address, state, qualifier, time);
    }
  }
}
