package com.digitalpetri.iec60870.catalog;

import com.digitalpetri.iec60870.address.CommonAddress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Evidence about the points present on one station, gathered by a client according to an {@link
 * ObservationMode}.
 *
 * <p>The name is intentional: these are points that were <em>seen</em> in traffic, not points that
 * are guaranteed to exist or to be fully described. An observed catalog is typically reconciled
 * against a configured {@link PointCatalog} (see {@link CatalogReconciliation}) or merged into one
 * (see {@link PointCatalog#merge}).
 *
 * @param station the common address of the station the points were observed on.
 * @param points the observed points; never null and copied defensively into an unmodifiable list.
 * @param observedAt the instant at which the observation completed.
 * @param mode the observation mode that produced this evidence.
 */
public record ObservedCatalog(
    CommonAddress station, List<ObservedPoint> points, Instant observedAt, ObservationMode mode) {

  /**
   * Validates the observed catalog and defensively copies the {@code points} list.
   *
   * @param station the common address of the station the points were observed on.
   * @param points the observed points; never null and copied defensively into an unmodifiable list.
   * @param observedAt the instant at which the observation completed.
   * @param mode the observation mode that produced this evidence.
   * @throws NullPointerException if any component, or any element of {@code points}, is null.
   */
  public ObservedCatalog {
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(points, "points");
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(mode, "mode");
    points = List.copyOf(points);
  }
}
