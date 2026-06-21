package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import io.netty.buffer.ByteBuf;

/**
 * C_SC_NA_1 (45) — single command without time tag.
 *
 * <p>Carries a single command state ({@code on}) together with a {@link QualifierOfCommand}. The
 * state and qualifier share a single SCO octet on the wire (IEC 60870-5-101 §7.2.6.15): bit 1 is
 * the single command state (SCS), bit 2 is reserved (zero), bits 3..7 are the qualifier of command
 * (QU), and bit 8 is the select/execute flag (S/E).
 *
 * @param address the information object address.
 * @param on the single command state (SCS); {@code true} to command ON, {@code false} to command
 *     OFF.
 * @param qualifier the qualifier of command (QU and S/E) controlling pulse duration and the
 *     select/execute semantics.
 */
public record SingleCommand(
    InformationObjectAddress address, boolean on, QualifierOfCommand qualifier)
    implements InformationObject {

  /** SCS — single command state bit (bit 1). */
  private static final int SCS_MASK = 0x01;

  /** Serde for the {@link SingleCommand} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SCO octet into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout — one octet (bit 1 = least significant bit): {@code S/E QU QU QU QU QU 0 SCS}.
     * The SCS bit (bit 1) carries the state and bits 3..8 carry the qualifier of command, formed by
     * {@link QualifierOfCommand#toBits()}.
     *
     * @param o the single command object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(SingleCommand o, ByteBuf buffer) {
      int octet = (o.on() ? SCS_MASK : 0) | o.qualifier().toBits();
      buffer.writeByte(octet);
    }

    /**
     * Decodes the SCO octet (IOA already read) from {@code buffer}; does not release the buffer.
     *
     * <p>Bit 1 yields {@code on}; bits 3..8 yield the {@link QualifierOfCommand} via {@link
     * QualifierOfCommand#fromBits(int)}.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded single command object.
     */
    public static SingleCommand decode(InformationObjectAddress address, ByteBuf buffer) {
      int octet = buffer.readUnsignedByte();
      boolean on = (octet & SCS_MASK) != 0;
      QualifierOfCommand qualifier = QualifierOfCommand.fromBits(octet);
      return new SingleCommand(address, on, qualifier);
    }
  }
}
