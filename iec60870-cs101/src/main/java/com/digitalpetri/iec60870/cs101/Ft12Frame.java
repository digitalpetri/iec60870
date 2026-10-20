package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.asdu.Asdu;
import java.util.Arrays;
import java.util.Objects;

/**
 * One FT1.2 link-layer frame in one of its three transmission formats.
 *
 * <p>FT1.2 (the format class FT 1.2 of IEC 60870-5-1, the link layer of IEC 60870-5-101) defines
 * three frame shapes:
 *
 * <ul>
 *   <li>{@link FixedLength} — a control field and link address only, used for link control and
 *       acknowledgements that carry no application data;
 *   <li>{@link Variable} — a control field, link address, and exactly one ASDU, used to transfer
 *       application data;
 *   <li>{@link SingleChar} — the single octet {@code 0xE5}, a compact positive acknowledgement.
 * </ul>
 *
 * <p>A received variable frame whose checksum is valid but whose ASDU cannot be decoded is
 * represented as an {@link UndecodableVariable}, so the link layer can still acknowledge it.
 *
 * @see Ft12Framer
 */
public sealed interface Ft12Frame
    permits Ft12Frame.FixedLength,
        Ft12Frame.Variable,
        Ft12Frame.UndecodableVariable,
        Ft12Frame.SingleChar {

  /**
   * A fixed-length FT1.2 frame: a link control field and link address with no ASDU.
   *
   * @param control the link control field.
   * @param linkAddress the link address, or {@code 0} when the address field is absent (balanced
   *     mode with a zero-octet address length).
   */
  record FixedLength(LinkControlField control, int linkAddress) implements Ft12Frame {}

  /**
   * A variable-length FT1.2 frame: a link control field and link address followed by exactly one
   * ASDU.
   *
   * @param control the link control field.
   * @param linkAddress the link address, or {@code 0} when the address field is absent (balanced
   *     mode with a zero-octet address length).
   * @param asdu the application service data unit carried by the frame.
   */
  record Variable(LinkControlField control, int linkAddress, Asdu asdu) implements Ft12Frame {}

  /**
   * A received variable-length FT1.2 frame whose framing and checksum are valid but whose ASDU
   * could not be decoded.
   *
   * <p>{@link Ft12Framer#decode} produces this frame instead of failing, so the link layer can run
   * its frame-count and acknowledgement procedure and report the octets through {@link
   * com.digitalpetri.iec60870.session.Session.Events#onUndecodableAsdu(byte[], Iec60870Exception)}.
   * It is inbound only; {@link Ft12Framer#encode} rejects it.
   *
   * @param control the link control field.
   * @param linkAddress the link address, or {@code 0} when the address field is absent.
   * @param asdu the raw ASDU octets, starting at the type identification.
   * @param cause the decode failure.
   */
  record UndecodableVariable(
      LinkControlField control, int linkAddress, byte[] asdu, Iec60870Exception cause)
      implements Ft12Frame {

    /**
     * Validates the components and copies the octets.
     *
     * @param control the link control field.
     * @param linkAddress the link address, or {@code 0} when the address field is absent.
     * @param asdu the raw ASDU octets, starting at the type identification.
     * @param cause the decode failure.
     * @throws NullPointerException if {@code control}, {@code asdu}, or {@code cause} is null.
     */
    public UndecodableVariable {
      Objects.requireNonNull(control, "control");
      asdu = Objects.requireNonNull(asdu, "asdu").clone();
      Objects.requireNonNull(cause, "cause");
    }

    /**
     * Returns a copy of the raw ASDU octets.
     *
     * @return the raw ASDU octets.
     */
    @Override
    public byte[] asdu() {
      return asdu.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof UndecodableVariable other
          && control.equals(other.control)
          && linkAddress == other.linkAddress
          && Arrays.equals(asdu, other.asdu)
          && cause.equals(other.cause);
    }

    @Override
    public int hashCode() {
      return Objects.hash(control, linkAddress, Arrays.hashCode(asdu), cause);
    }

    @Override
    public String toString() {
      return "UndecodableVariable[control="
          + control
          + ", linkAddress="
          + linkAddress
          + ", asdu="
          + Arrays.toString(asdu)
          + ", cause="
          + cause
          + "]";
    }
  }

  /**
   * The single-character FT1.2 frame: the one octet {@code 0xE5}, a positive acknowledgement that
   * carries neither a control field nor a link address.
   */
  record SingleChar() implements Ft12Frame {}
}
