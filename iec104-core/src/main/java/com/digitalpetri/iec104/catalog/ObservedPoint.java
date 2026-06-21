package com.digitalpetri.iec104.catalog;

import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.Cause;
import java.util.Objects;

/**
 * A single point seen while building an {@link ObservedCatalog}, recording the address together
 * with the type identification and cause of transmission observed on the wire.
 *
 * <p>The recorded {@link AsduType} and {@link Cause} are evidence of what the station actually
 * reported for the point, which a {@link CatalogReconciliation} can compare against a configured
 * catalog's expectations.
 *
 * @param address the fully qualified address of the observed point.
 * @param observedType the type identification of the ASDU the point was carried in.
 * @param observedCause the cause of transmission of the ASDU the point was carried in.
 * @param confidence how the point was observed and therefore how much to trust the evidence.
 */
public record ObservedPoint(
    PointAddress address,
    AsduType observedType,
    Cause observedCause,
    ObservationConfidence confidence) {

  /**
   * Validates the observed point.
   *
   * @param address the fully qualified address of the observed point.
   * @param observedType the type identification of the ASDU the point was carried in.
   * @param observedCause the cause of transmission of the ASDU the point was carried in.
   * @param confidence how the point was observed and therefore how much to trust the evidence.
   * @throws NullPointerException if any component is null.
   */
  public ObservedPoint {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(observedType, "observedType");
    Objects.requireNonNull(observedCause, "observedCause");
    Objects.requireNonNull(confidence, "confidence");
  }
}
