package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfSetpoint;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_SE_NB_1 (49) — set-point command, scaled value.
 *
 * <p>Carries a signed 16-bit scaled set-point value (SVA) accompanied by a qualifier of set-point
 * command (QOS) that selects between select and execute semantics. Used in the control direction
 * with causes activation ({@code <6>}) and deactivation ({@code <8>}).
 *
 * @param address the information object address.
 * @param value the scaled set-point value (SVA, signed 16-bit).
 * @param qualifier the qualifier of set-point command (QOS).
 */
public record SetpointScaled(
    InformationObjectAddress address, short value, QualifierOfSetpoint qualifier)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the scaled set-point value (SVA, signed 16-bit).
   * @param qualifier the qualifier of set-point command (QOS).
   * @throws NullPointerException if {@code address} or {@code qualifier} is null.
   */
  public SetpointScaled {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link SetpointScaled} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (little-endian): octets 1..2 are the SVA scaled value (signed 16-bit, least
     * significant octet first), octet 3 is the QOS ({@code QL} in bits 1..7, {@code S/E} in bit 8).
     *
     * @param o the set-point command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(SetpointScaled o, ByteBuf buffer) {
      buffer.writeShortLE(o.value());
      QualifierOfSetpoint.Serde.encode(o.qualifier(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the two-octet SVA scaled value followed by the QOS octet. Does not release the
     * buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded set-point command.
     */
    public static SetpointScaled decode(InformationObjectAddress address, ByteBuf buffer) {
      short value = buffer.readShortLE();
      QualifierOfSetpoint qualifier = QualifierOfSetpoint.Serde.decode(buffer);
      return new SetpointScaled(address, value, qualifier);
    }
  }
}
