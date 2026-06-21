package com.digitalpetri.iec104.catalog;

import java.time.Duration;
import java.util.Objects;

/**
 * How a client gathers evidence about which points exist on a station when building an {@link
 * ObservedCatalog}.
 *
 * <p>These are deliberately named <em>observation</em> modes rather than discovery modes: IEC 104
 * has no authoritative browse operation, so every mode produces evidence of points seen, not a
 * guaranteed-complete inventory. Choose a mode with the static factories.
 *
 * <p>Example:
 *
 * <pre>{@code
 * ObservationMode station = ObservationMode.generalInterrogation();
 * ObservationMode group = ObservationMode.groupInterrogation(3);
 * ObservationMode passive = ObservationMode.passiveTraffic(Duration.ofMinutes(5));
 * ObservationMode seeded = ObservationMode.generalThenPassive(Duration.ofMinutes(5));
 * }</pre>
 */
public sealed interface ObservationMode
    permits ObservationMode.GeneralInterrogation,
        ObservationMode.GroupInterrogation,
        ObservationMode.PassiveTraffic,
        ObservationMode.GeneralThenPassive {

  /**
   * Observes by sending a station (general) interrogation, qualifier of interrogation {@code 20},
   * and recording the reported snapshot.
   *
   * @return a general-interrogation observation mode.
   */
  static ObservationMode generalInterrogation() {
    return new GeneralInterrogation();
  }

  /**
   * Observes by sending a group interrogation for one of the sixteen interrogation groups.
   *
   * @param groupNumber the interrogation group, in the range {@code 1..16}.
   * @return a group-interrogation observation mode.
   * @throws IllegalArgumentException if {@code groupNumber} is not in the range {@code 1..16}.
   */
  static ObservationMode groupInterrogation(int groupNumber) {
    return new GroupInterrogation(groupNumber);
  }

  /**
   * Observes passively by watching the ASDUs already flowing for the given window, sending no
   * interrogation.
   *
   * @param window the duration to watch traffic for.
   * @return a passive-traffic observation mode.
   * @throws NullPointerException if {@code window} is null.
   * @throws IllegalArgumentException if {@code window} is negative.
   */
  static ObservationMode passiveTraffic(Duration window) {
    return new PassiveTraffic(window);
  }

  /**
   * Observes by sending a station interrogation once and then watching traffic passively for the
   * given window.
   *
   * @param passiveWindow the duration to watch traffic for after the interrogation.
   * @return a general-then-passive observation mode.
   * @throws NullPointerException if {@code passiveWindow} is null.
   * @throws IllegalArgumentException if {@code passiveWindow} is negative.
   */
  static ObservationMode generalThenPassive(Duration passiveWindow) {
    return new GeneralThenPassive(passiveWindow);
  }

  /** Observation by station (general) interrogation. */
  record GeneralInterrogation() implements ObservationMode {}

  /**
   * Observation by group interrogation.
   *
   * @param groupNumber the interrogation group, in the range {@code 1..16}.
   */
  record GroupInterrogation(int groupNumber) implements ObservationMode {

    /**
     * Validates the group number.
     *
     * @param groupNumber the interrogation group, in the range {@code 1..16}.
     * @throws IllegalArgumentException if {@code groupNumber} is not in the range {@code 1..16}.
     */
    public GroupInterrogation {
      if (groupNumber < 1 || groupNumber > 16) {
        throw new IllegalArgumentException("interrogation group must be in 1..16: " + groupNumber);
      }
    }
  }

  /**
   * Observation by passively watching traffic for a window.
   *
   * @param window the duration to watch traffic for.
   */
  record PassiveTraffic(Duration window) implements ObservationMode {

    /**
     * Validates the window.
     *
     * @param window the duration to watch traffic for.
     * @throws NullPointerException if {@code window} is null.
     * @throws IllegalArgumentException if {@code window} is negative.
     */
    public PassiveTraffic {
      Objects.requireNonNull(window, "window");
      if (window.isNegative()) {
        throw new IllegalArgumentException("window must not be negative: " + window);
      }
    }
  }

  /**
   * Observation by a station interrogation followed by a passive-watch window.
   *
   * @param passiveWindow the duration to watch traffic for after the interrogation.
   */
  record GeneralThenPassive(Duration passiveWindow) implements ObservationMode {

    /**
     * Validates the passive window.
     *
     * @param passiveWindow the duration to watch traffic for after the interrogation.
     * @throws NullPointerException if {@code passiveWindow} is null.
     * @throws IllegalArgumentException if {@code passiveWindow} is negative.
     */
    public GeneralThenPassive {
      Objects.requireNonNull(passiveWindow, "passiveWindow");
      if (passiveWindow.isNegative()) {
        throw new IllegalArgumentException("passiveWindow must not be negative: " + passiveWindow);
      }
    }
  }
}
