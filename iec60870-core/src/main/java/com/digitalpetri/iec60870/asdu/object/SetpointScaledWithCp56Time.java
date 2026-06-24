package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfSetpoint;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_SE_TB_1 (62) — set-point command, scaled value, with a CP56Time2a time tag.
 *
 * <p>A set-point command in control direction that carries a signed 16-bit scaled set-point value
 * (SVA), a qualifier of set-point command (QOS) selecting between select and execute semantics, and
 * a seven-octet absolute time tag. Used with causes activation ({@code <6>}) and deactivation
 * ({@code <8>}); confirmed in monitor direction. Because each command carries its own time tag,
 * this type is only transmitted as a single information object (SQ = 0).
 *
 * @param address the information object address.
 * @param value the scaled set-point value (SVA, signed 16-bit).
 * @param qualifier the qualifier of set-point command (QOS).
 * @param time the CP56Time2a time tag.
 */
public record SetpointScaledWithCp56Time(
    InformationObjectAddress address, short value, QualifierOfSetpoint qualifier, Cp56Time2a time)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the scaled set-point value (SVA, signed 16-bit).
   * @param qualifier the qualifier of set-point command (QOS).
   * @param time the CP56Time2a time tag.
   * @throws NullPointerException if {@code address}, {@code qualifier}, or {@code time} is null.
   */
  public SetpointScaledWithCp56Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
    Objects.requireNonNull(time, "time");
  }

  /** Serde for the {@link SetpointScaledWithCp56Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SVA, QOS, and CP56Time2a elements into {@code buffer}; does not write the IOA or
     * release the buffer.
     *
     * <p>Wire layout (Mode 1, least significant octet first): octets 1..2 = SVA scaled value
     * (signed 16-bit, little-endian), octet 3 = QOS ({@code QL} in bits 1..7, {@code S/E} in bit
     * 8), octets 4..10 = CP56Time2a.
     *
     * @param o the set-point command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(SetpointScaledWithCp56Time o, ByteBuf buffer) {
      buffer.writeShortLE(o.value());
      QualifierOfSetpoint.Serde.encode(o.qualifier(), buffer);
      Cp56Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the SVA, QOS, and CP56Time2a elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the two-octet SVA scaled value, the QOS octet, and the seven-octet CP56Time2a time
     * tag. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded set-point command.
     */
    public static SetpointScaledWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      short value = buffer.readShortLE();
      QualifierOfSetpoint qualifier = QualifierOfSetpoint.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new SetpointScaledWithCp56Time(address, value, qualifier, time);
    }
  }
}
