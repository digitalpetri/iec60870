package com.digitalpetri.iec60870.cs101;

/**
 * The one-octet link control field that begins the user data of every FT1.2 fixed- and
 * variable-length frame.
 *
 * <p>The field carries the direction bit (DIR), the primary/secondary bit (PRM), a function-class
 * bit pair whose meaning depends on PRM, and a four-bit function code. When PRM is {@code true} the
 * frame originates from the primary station and the bit pair is the frame count bit (FCB) and frame
 * count valid bit (FCV); when PRM is {@code false} the frame is a secondary response and the bit
 * pair is the access demand bit (ACD) and data flow control bit (DFC). Use {@link #fcb()}/{@link
 * #fcv()} on primary frames and {@link #acd()}/{@link #dfc()} on secondary frames; both pairs alias
 * the same two stored bits.
 *
 * <p>Bit layout of the control octet:
 *
 * <ul>
 *   <li>bit 7 ({@code 0x80}) — DIR;
 *   <li>bit 6 ({@code 0x40}) — PRM;
 *   <li>bit 5 ({@code 0x20}) — FCB (primary) / ACD (secondary);
 *   <li>bit 4 ({@code 0x10}) — FCV (primary) / DFC (secondary);
 *   <li>bits 3..0 ({@code 0x0F}) — function code.
 * </ul>
 *
 * @param prm the primary/secondary bit; {@code true} marks a primary (request) frame, {@code false}
 *     a secondary (response) frame.
 * @param fcbOrAcd the frame count bit when {@code prm} is {@code true}, otherwise the access demand
 *     bit.
 * @param fcvOrDfc the frame count valid bit when {@code prm} is {@code true}, otherwise the data
 *     flow control bit.
 * @param dir the direction bit.
 * @param functionCode the link-layer function code, in the range {@code 0..15}.
 */
public record LinkControlField(
    boolean prm, boolean fcbOrAcd, boolean fcvOrDfc, boolean dir, int functionCode) {

  /** Bit mask of the direction (DIR) bit within the control octet. */
  private static final int DIR_MASK = 0x80;

  /** Bit mask of the primary/secondary (PRM) bit within the control octet. */
  private static final int PRM_MASK = 0x40;

  /** Bit mask of the FCB (primary) / ACD (secondary) bit within the control octet. */
  private static final int FCB_ACD_MASK = 0x20;

  /** Bit mask of the FCV (primary) / DFC (secondary) bit within the control octet. */
  private static final int FCV_DFC_MASK = 0x10;

  /** Bit mask of the four-bit function code within the control octet. */
  private static final int FUNCTION_CODE_MASK = 0x0F;

  /**
   * Validates the function code.
   *
   * @param prm the primary/secondary bit; {@code true} marks a primary (request) frame, {@code
   *     false} a secondary (response) frame.
   * @param fcbOrAcd the frame count bit when {@code prm} is {@code true}, otherwise the access
   *     demand bit.
   * @param fcvOrDfc the frame count valid bit when {@code prm} is {@code true}, otherwise the data
   *     flow control bit.
   * @param dir the direction bit.
   * @param functionCode the link-layer function code, in the range {@code 0..15}.
   * @throws IllegalArgumentException if {@code functionCode} is outside {@code 0..15}.
   */
  public LinkControlField {
    if (functionCode < 0 || functionCode > 15) {
      throw new IllegalArgumentException("functionCode must be in range 0..15: " + functionCode);
    }
  }

  /**
   * Creates a primary (request) control field.
   *
   * @param dir the direction bit.
   * @param fcb the frame count bit.
   * @param fcv the frame count valid bit.
   * @param functionCode the primary function code, in the range {@code 0..15}.
   * @return the primary control field.
   * @throws IllegalArgumentException if {@code functionCode} is outside {@code 0..15}.
   */
  public static LinkControlField primary(boolean dir, boolean fcb, boolean fcv, int functionCode) {
    return new LinkControlField(true, fcb, fcv, dir, functionCode);
  }

  /**
   * Creates a secondary (response) control field.
   *
   * @param dir the direction bit.
   * @param acd the access demand bit.
   * @param dfc the data flow control bit.
   * @param functionCode the secondary function code, in the range {@code 0..15}.
   * @return the secondary control field.
   * @throws IllegalArgumentException if {@code functionCode} is outside {@code 0..15}.
   */
  public static LinkControlField secondary(
      boolean dir, boolean acd, boolean dfc, int functionCode) {
    return new LinkControlField(false, acd, dfc, dir, functionCode);
  }

  /**
   * Returns the frame count bit (FCB), meaningful only on a primary frame.
   *
   * @return the FCB; aliases the {@link #fcbOrAcd()} bit.
   */
  public boolean fcb() {
    return fcbOrAcd;
  }

  /**
   * Returns the frame count valid bit (FCV), meaningful only on a primary frame.
   *
   * @return the FCV; aliases the {@link #fcvOrDfc()} bit.
   */
  public boolean fcv() {
    return fcvOrDfc;
  }

  /**
   * Returns the access demand bit (ACD), meaningful only on a secondary frame.
   *
   * @return the ACD; aliases the {@link #fcbOrAcd()} bit.
   */
  public boolean acd() {
    return fcbOrAcd;
  }

  /**
   * Returns the data flow control bit (DFC), meaningful only on a secondary frame.
   *
   * @return the DFC; aliases the {@link #fcvOrDfc()} bit.
   */
  public boolean dfc() {
    return fcvOrDfc;
  }

  /**
   * Encodes this control field as its one-octet wire value.
   *
   * @return the control octet in the range {@code 0..255}.
   */
  public int toOctet() {
    return (dir ? DIR_MASK : 0)
        | (prm ? PRM_MASK : 0)
        | (fcbOrAcd ? FCB_ACD_MASK : 0)
        | (fcvOrDfc ? FCV_DFC_MASK : 0)
        | (functionCode & FUNCTION_CODE_MASK);
  }

  /**
   * Decodes a one-octet control field value.
   *
   * @param octet the control octet; only its low eight bits are inspected.
   * @return the decoded control field.
   */
  public static LinkControlField fromOctet(int octet) {
    boolean dir = (octet & DIR_MASK) != 0;
    boolean prm = (octet & PRM_MASK) != 0;
    boolean fcbOrAcd = (octet & FCB_ACD_MASK) != 0;
    boolean fcvOrDfc = (octet & FCV_DFC_MASK) != 0;
    int functionCode = octet & FUNCTION_CODE_MASK;
    return new LinkControlField(prm, fcbOrAcd, fcvOrDfc, dir, functionCode);
  }
}
