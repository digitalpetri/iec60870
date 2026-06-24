package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_SC_TA_1 (58) — single command with a CP56Time2a time tag (IEC 60870-5-104 §8.1).
 *
 * <p>This control-direction ASDU carries a single command (SCO) together with a seven-octet time
 * tag recording when the command was initiated at the controlling station. A controlled station
 * that receives the command after the maximum allowable delay passes it to the application marked
 * as "too late" and performs no command action. Because every command carries its own time tag,
 * this type always appears as a single information object (SQ = 0).
 *
 * <p>The {@code on} state and the {@link QualifierOfCommand qualifier} share a single SCO octet:
 * the single command state ({@code SCS}) occupies bit 1, the qualifier of command ({@code QU})
 * occupies bits 3..7, and the select/execute flag ({@code S/E}) occupies bit 8. Bit 2 is reserved
 * and is zero.
 *
 * @param address the information object address.
 * @param on the single command state (SCS): {@code true} = ON, {@code false} = OFF.
 * @param qualifier the qualifier of command (QU plus the S/E select/execute flag).
 * @param time the CP56Time2a time tag recording when the command was initiated.
 */
public record SingleCommandWithCp56Time(
    InformationObjectAddress address, boolean on, QualifierOfCommand qualifier, Cp56Time2a time)
    implements InformationObject {

  /** SCS — single command state bit (bit 1). */
  private static final int SCS_MASK = 0x01;

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param on the single command state (SCS).
   * @param qualifier the qualifier of command (QU and S/E).
   * @param time the CP56Time2a time tag.
   * @throws NullPointerException if {@code address}, {@code qualifier}, or {@code time} is null.
   */
  public SingleCommandWithCp56Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
    Objects.requireNonNull(time, "time");
  }

  /**
   * Serde for the {@link SingleCommandWithCp56Time} information elements (after the IOA).
   *
   * <p>Wire layout (Mode 1, least significant octet first), eight octets total:
   *
   * <ul>
   *   <li>octet 1: SCO — SCS(b1), reserved(b2)=0, QU(b3..7), S/E(b8); the qualifier bits come from
   *       {@link QualifierOfCommand#toBits()};
   *   <li>octets 2-8: CP56Time2a, as encoded by {@link Cp56Time2a.Serde}.
   * </ul>
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SCO octet and CP56Time2a into {@code buffer}; does not write the IOA or release
     * the buffer.
     *
     * @param o the object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(SingleCommandWithCp56Time o, ByteBuf buffer) {
      int octet = (o.on() ? SCS_MASK : 0) | o.qualifier().toBits();
      buffer.writeByte(octet);

      Cp56Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer positioned at the SCO octet.
     * @return the decoded object.
     */
    public static SingleCommandWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      int octet = buffer.readUnsignedByte();
      boolean on = (octet & SCS_MASK) != 0;
      QualifierOfCommand qualifier = QualifierOfCommand.fromBits(octet);

      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);

      return new SingleCommandWithCp56Time(address, on, qualifier, time);
    }
  }
}
