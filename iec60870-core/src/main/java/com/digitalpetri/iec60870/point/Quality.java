package com.digitalpetri.iec60870.point;

import com.digitalpetri.iec60870.asdu.element.Qds;

/**
 * The quality of a point value, expressed as the five standard IEC 60870-5 quality flags.
 *
 * <p>This is the point-model counterpart of the wire-level {@link Qds} quality descriptor. All
 * flags default to {@code false}, which represents good quality; {@link #good()} and {@link
 * #invalidQuality()} provide the two most common cases.
 *
 * <p>Example:
 *
 * <pre>{@code
 * Quality q = Quality.good().withInvalid(true);
 * Qds wire = q.toQds();
 * }</pre>
 *
 * @param overflow whether the value is beyond a predefined range (OV).
 * @param blocked whether the value is blocked for transmission (BL).
 * @param substituted whether the value was substituted by an operator or automatic source (SB).
 * @param notTopical whether the value was not refreshed within the expected interval (NT).
 * @param invalid whether the value could not be correctly acquired (IV).
 */
public record Quality(
    boolean overflow, boolean blocked, boolean substituted, boolean notTopical, boolean invalid) {

  /**
   * Returns a quality with every flag clear, representing good quality.
   *
   * @return a quality with all flags {@code false}.
   */
  public static Quality good() {
    return new Quality(false, false, false, false, false);
  }

  /**
   * Returns a quality with only the {@code invalid} flag set.
   *
   * <p>Named {@code invalidQuality} rather than {@code invalid} to avoid clashing with the {@link
   * #invalid()} record component accessor.
   *
   * @return a quality marked invalid.
   */
  public static Quality invalidQuality() {
    return new Quality(false, false, false, false, true);
  }

  /**
   * Returns a copy of this quality with the {@code invalid} flag set to {@code value}.
   *
   * @param value the new value of the invalid flag.
   * @return a quality equal to this one but with the given invalid flag.
   */
  public Quality withInvalid(boolean value) {
    return new Quality(overflow, blocked, substituted, notTopical, value);
  }

  /**
   * Converts this quality to its wire-level {@link Qds} quality descriptor.
   *
   * @return the equivalent {@link Qds}.
   */
  public Qds toQds() {
    return new Qds(overflow, blocked, substituted, notTopical, invalid);
  }

  /**
   * Creates a {@link Quality} from a wire-level {@link Qds} quality descriptor.
   *
   * @param qds the wire-level quality descriptor.
   * @return the equivalent point-model quality.
   */
  public static Quality fromQds(Qds qds) {
    return new Quality(
        qds.overflow(), qds.blocked(), qds.substituted(), qds.notTopical(), qds.invalid());
  }
}
