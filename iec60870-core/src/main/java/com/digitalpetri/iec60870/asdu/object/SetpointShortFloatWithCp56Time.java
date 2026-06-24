package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfSetpoint;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_SE_TC_1 (63) — set-point command, short floating point number, with a CP56Time2a time tag.
 *
 * <p>A set-point command in control direction that carries an IEEE 754 short floating point
 * set-point value, a qualifier of set-point command (select/execute and QL), and a seven-octet
 * absolute time tag. Sent with cause of transmission {@code activation} or {@code deactivation};
 * confirmed in monitor direction. Because each command carries its own time tag, this type is only
 * transmitted as a single information object (SQ = 0).
 *
 * @param address the information object address.
 * @param value the short floating point set-point value (IEEE 754, R32).
 * @param qualifier the qualifier of set-point command (QOS).
 * @param time the CP56Time2a time tag.
 */
public record SetpointShortFloatWithCp56Time(
    InformationObjectAddress address, float value, QualifierOfSetpoint qualifier, Cp56Time2a time)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the short floating point set-point value (IEEE 754, R32).
   * @param qualifier the qualifier of set-point command (QOS).
   * @param time the CP56Time2a time tag.
   * @throws NullPointerException if {@code address}, {@code qualifier}, or {@code time} is null.
   */
  public SetpointShortFloatWithCp56Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
    Objects.requireNonNull(time, "time");
  }

  /** Serde for the {@link SetpointShortFloatWithCp56Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the R32, QOS, and CP56Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first): octets 1..4 = IEEE 754 short floating
     * point value (little-endian), octet 5 = QOS ({@code QL} in bits 1..7, {@code S/E} in bit 8),
     * octets 6..12 = CP56Time2a. The information object address is not written here. Does not
     * release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(SetpointShortFloatWithCp56Time object, ByteBuf buffer) {
      buffer.writeFloatLE(object.value());
      QualifierOfSetpoint.Serde.encode(object.qualifier(), buffer);
      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the R32, QOS, and CP56Time2a elements (information object address already read) from
     * {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static SetpointShortFloatWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      float value = buffer.readFloatLE();
      QualifierOfSetpoint qualifier = QualifierOfSetpoint.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new SetpointShortFloatWithCp56Time(address, value, qualifier, time);
    }
  }
}
