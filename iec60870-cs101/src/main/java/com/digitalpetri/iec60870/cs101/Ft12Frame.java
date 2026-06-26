package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.asdu.Asdu;

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
 * @see Ft12Framer
 */
public sealed interface Ft12Frame
    permits Ft12Frame.FixedLength, Ft12Frame.Variable, Ft12Frame.SingleChar {

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
   * The single-character FT1.2 frame: the one octet {@code 0xE5}, a positive acknowledgement that
   * carries neither a control field nor a link address.
   */
  record SingleChar() implements Ft12Frame {}
}
