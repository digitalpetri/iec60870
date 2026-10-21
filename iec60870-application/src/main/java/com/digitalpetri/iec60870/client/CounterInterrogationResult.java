package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.TimePolicy;
import java.util.List;
import java.util.Objects;

/**
 * The result of a counter interrogation (C_CI_NA_1).
 *
 * <p>For a read ({@link FreezeMode#READ}), {@link #objects()} holds the integrated totals the
 * station reported for the requested group between its activation confirmation and activation
 * termination, in receive order. A freeze, freeze with reset, or reset completes on the activation
 * confirmation and reports no counters, so {@link #objects()} is empty. Read the frozen values with
 * a later counter read. {@link #timePolicy()} is the policy {@link #pointValues()} resolves time
 * tags with; a result from the client carries {@link ClientConfig#timePolicy()}.
 *
 * <pre>{@code
 * client.counterInterrogate(station, QualifierOfCounterInterrogation.general(FreezeMode.FREEZE_NO_RESET));
 * CounterInterrogationResult counters = client.counterInterrogate(station);
 * for (InterrogationResult.PointEntry entry : counters.pointValues()) {
 *   System.out.println(entry.address() + " = " + entry.value());
 * }
 * }</pre>
 *
 * @param station the common address that was interrogated.
 * @param qualifier the qualifier of counter interrogation that was sent.
 * @param objects the integrated totals reported by the station, in receive order; empty unless
 *     {@code qualifier} requests a read.
 * @param timePolicy the policy that {@link #pointValues()} resolves time tags with.
 */
public record CounterInterrogationResult(
    CommonAddress station,
    QualifierOfCounterInterrogation qualifier,
    List<InformationObject> objects,
    TimePolicy timePolicy) {

  /**
   * Validates the result and defensively copies the objects list.
   *
   * @param station the common address that was interrogated.
   * @param qualifier the qualifier of counter interrogation that was sent.
   * @param objects the integrated totals reported by the station, in receive order.
   * @param timePolicy the policy that {@link #pointValues()} resolves time tags with.
   * @throws NullPointerException if any component is null.
   */
  public CounterInterrogationResult {
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(qualifier, "qualifier");
    Objects.requireNonNull(timePolicy, "timePolicy");
    objects = List.copyOf(objects);
  }

  /**
   * Creates a result whose {@link #pointValues()} resolves time tags with {@link TimePolicy#utc()}.
   *
   * @param station the common address that was interrogated.
   * @param qualifier the qualifier of counter interrogation that was sent.
   * @param objects the integrated totals reported by the station, in receive order.
   * @throws NullPointerException if any argument is null.
   */
  public CounterInterrogationResult(
      CommonAddress station,
      QualifierOfCounterInterrogation qualifier,
      List<InformationObject> objects) {
    this(station, qualifier, objects, TimePolicy.utc());
  }

  /**
   * Projects the reported counters onto domain point values, resolving time tags with {@link
   * #timePolicy()}.
   *
   * @return the counter values, in receive order.
   * @see #pointValues(TimePolicy)
   */
  public List<InterrogationResult.PointEntry> pointValues() {
    return pointValues(timePolicy);
  }

  /**
   * Projects the reported counters onto domain point values, resolving time tags with {@code
   * policy}.
   *
   * <p>Each entry pairs the counter's {@link PointAddress} with its {@link PointValue}. CP24Time2a
   * tags are never completed here, as for {@link InterrogationResult#pointValues(TimePolicy)}.
   *
   * @param policy the policy that resolves time tags.
   * @return the counter values, in receive order.
   * @throws NullPointerException if {@code policy} is null.
   */
  public List<InterrogationResult.PointEntry> pointValues(TimePolicy policy) {
    return new InterrogationResult(station, objects, true).pointValues(policy);
  }
}
