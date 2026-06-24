package com.digitalpetri.iec60870.asdu.element;

import io.netty.buffer.ByteBuf;

/**
 * OCI — output circuit information of protection equipment (IEC 60870-5-101 §7.2.6.12).
 *
 * <p>A bit string flagging which output-circuit commands the protection equipment has issued.
 * Reserved bits 5..8 are ignored on decode and written as zero on encode.
 *
 * @param generalCommand whether a general command to the output circuit was issued (GC).
 * @param commandL1 whether a command to the output circuit of phase L1 was issued (CL1).
 * @param commandL2 whether a command to the output circuit of phase L2 was issued (CL2).
 * @param commandL3 whether a command to the output circuit of phase L3 was issued (CL3).
 */
public record Oci(boolean generalCommand, boolean commandL1, boolean commandL2, boolean commandL3) {

  /** GC — general-command bit (bit 1). */
  private static final int GC_MASK = 0x01;

  /** CL1 — command phase L1 bit (bit 2). */
  private static final int CL1_MASK = 0x02;

  /** CL2 — command phase L2 bit (bit 3). */
  private static final int CL2_MASK = 0x04;

  /** CL3 — command phase L3 bit (bit 4). */
  private static final int CL3_MASK = 0x08;

  /** Serde for the {@link Oci} element, encoded as a single octet. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the output-circuit flags as one octet into {@code buffer}.
     *
     * <p>Wire layout (bit 1 = least significant bit): GC(b1), CL1(b2), CL2(b3), CL3(b4); reserved
     * bits 5..8 are written as zero. Does not release the buffer.
     *
     * @param oci the element to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Oci oci, ByteBuf buffer) {
      int b = 0;
      if (oci.generalCommand()) {
        b |= GC_MASK;
      }
      if (oci.commandL1()) {
        b |= CL1_MASK;
      }
      if (oci.commandL2()) {
        b |= CL2_MASK;
      }
      if (oci.commandL3()) {
        b |= CL3_MASK;
      }
      buffer.writeByte(b);
    }

    /**
     * Decodes one octet from {@code buffer} into an {@link Oci}.
     *
     * <p>Reserved bits 5..8 are ignored. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded element.
     */
    public static Oci decode(ByteBuf buffer) {
      int b = buffer.readUnsignedByte();
      return new Oci(
          (b & GC_MASK) != 0, (b & CL1_MASK) != 0, (b & CL2_MASK) != 0, (b & CL3_MASK) != 0);
    }
  }
}
