package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import java.time.Duration;
import java.util.Objects;

/**
 * The result of a positively confirmed test command (C_TS_TA_1 or C_TS_NA_1).
 *
 * <p>The round-trip time runs from handing the activation to the session until the matching
 * activation confirmation arrived. For C_TS_TA_1 the {@link #confirmation()} carries the echoed
 * test sequence counter and time, which a caller can compare with what it sent.
 *
 * @param station the common address that was tested.
 * @param roundTripTime the time between sending the activation and receiving its confirmation;
 *     never negative.
 * @param confirmation the confirming ASDU.
 */
public record TestResult(CommonAddress station, Duration roundTripTime, Asdu confirmation) {

  /**
   * Validates the components of the result.
   *
   * @param station the common address that was tested.
   * @param roundTripTime the time between sending the activation and receiving its confirmation.
   * @param confirmation the confirming ASDU.
   * @throws NullPointerException if any component is null.
   * @throws IllegalArgumentException if {@code roundTripTime} is negative.
   */
  public TestResult {
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(roundTripTime, "roundTripTime");
    Objects.requireNonNull(confirmation, "confirmation");
    if (roundTripTime.isNegative()) {
      throw new IllegalArgumentException("roundTripTime must not be negative: " + roundTripTime);
    }
  }
}
