package com.digitalpetri.iec60870.asdu.element;

/**
 * Qualifier of command (QOC) per IEC 60870-5-101 clause 7.2.6.26.
 *
 * <p>The QOC is not an independent octet on the wire: it occupies the high six bits of the single
 * command (SCO), double command (DCO), and regulating step command (RCO) octets. Within that octet
 * the qualifier of command ({@code QU}, a five-bit field) occupies bits 3..7 and the select/execute
 * flag ({@code S/E}) occupies bit 8. The owning command record composes the full octet by combining
 * its low-bit state field with {@link #toBits()}.
 *
 * <p>Standard {@code QU} values are: {@code 0} no additional definition; {@code 1} short pulse;
 * {@code 2} long pulse; {@code 3} persistent output. The remaining values are reserved.
 *
 * @param qualifier the {@code QU} field, in the range {@code 0..31}.
 * @param select {@code true} for a select command ({@code S/E = 1}); {@code false} for an execute
 *     command ({@code S/E = 0}).
 */
public record QualifierOfCommand(int qualifier, boolean select) {

  /** Bit mask of the select/execute flag (bit 8) within the command octet. */
  private static final int SELECT_MASK = 0x80;

  /** Maximum value of the five-bit {@code QU} field. */
  private static final int MAX_QUALIFIER = 31;

  /**
   * Validates the qualifier range.
   *
   * @param qualifier the {@code QU} field, in the range {@code 0..31}.
   * @param select {@code true} for a select command ({@code S/E = 1}); {@code false} for an execute
   *     command ({@code S/E = 0}).
   * @throws IllegalArgumentException if {@code qualifier} is outside {@code 0..31}.
   */
  public QualifierOfCommand {
    if (qualifier < 0 || qualifier > MAX_QUALIFIER) {
      throw new IllegalArgumentException("qualifier out of range [0, 31]: " + qualifier);
    }
  }

  /**
   * Returns the high six bits of the command octet contributed by this qualifier, i.e. the {@code
   * QU} field shifted into bits 3..7 combined with the {@code S/E} flag in bit 8.
   *
   * <p>The owning command record OR-combines this value with its two-bit (or one-bit) state field
   * in bits 1..2 to form the complete octet.
   *
   * @return the qualifier bits, with {@code QU} in bits 3..7 and {@code S/E} in bit 8.
   */
  public int toBits() {
    return (qualifier << 2) | (select ? SELECT_MASK : 0);
  }

  /**
   * Extracts the qualifier of command from a complete SCO/DCO/RCO octet, ignoring the low state
   * bits.
   *
   * @param octet the complete command octet, in the range {@code 0..255}.
   * @return the {@link QualifierOfCommand} described by bits 3..8 of {@code octet}.
   */
  public static QualifierOfCommand fromBits(int octet) {
    int qualifier = (octet >> 2) & MAX_QUALIFIER;
    boolean select = (octet & SELECT_MASK) != 0;
    return new QualifierOfCommand(qualifier, select);
  }
}
