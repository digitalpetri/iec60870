package com.digitalpetri.iec104.client;

import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.point.MonitorMapping;
import com.digitalpetri.iec104.point.PointValue;
import com.digitalpetri.iec104.point.PointValueExtraction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of a general or group interrogation: the snapshot of information objects the
 * controlled station reported between the activation confirmation and the activation termination.
 *
 * <p>{@link #objects()} carries the raw information objects in the order received. Use {@link
 * #pointValues()} to project the monitor objects onto domain {@link PointValue} entries keyed by
 * {@link PointAddress}; objects that are not monitor types are skipped. {@link #terminated()}
 * reports whether the station sent an activation termination ({@code ACT_TERM}); a {@code false}
 * value indicates the collection ended for another reason, such as a timeout.
 *
 * <pre>{@code
 * InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
 * for (InterrogationResult.PointEntry entry : snapshot.pointValues()) {
 *   System.out.println(entry.address() + " = " + entry.value());
 * }
 * }</pre>
 *
 * @param station the common address that was interrogated.
 * @param objects the information objects reported by the station, in receive order.
 * @param terminated {@code true} if the station sent an activation termination, {@code false}
 *     otherwise.
 */
public record InterrogationResult(
    CommonAddress station, List<InformationObject> objects, boolean terminated) {

  /**
   * Validates the result and defensively copies the objects list.
   *
   * @param station the common address that was interrogated.
   * @param objects the information objects reported by the station, in receive order.
   * @param terminated {@code true} if the station sent an activation termination, {@code false}
   *     otherwise.
   * @throws NullPointerException if {@code station} or {@code objects} is null.
   */
  public InterrogationResult {
    Objects.requireNonNull(station, "station");
    objects = List.copyOf(objects);
  }

  /**
   * Projects the reported monitor objects onto domain point values.
   *
   * <p>Each entry pairs the object's {@link PointAddress} (the interrogated {@link #station()} plus
   * the object's information object address) with the extracted {@link PointValue}. Objects that
   * are not supported monitor types are skipped.
   *
   * @return the point values reported by the interrogation, in receive order.
   */
  public List<PointEntry> pointValues() {
    List<PointEntry> entries = new ArrayList<>();
    for (InformationObject object : objects) {
      Optional<PointValueExtraction> extraction = MonitorMapping.extract(object);
      if (extraction.isEmpty()) {
        continue;
      }
      PointAddress address = new PointAddress(station, object.address());
      entries.add(new PointEntry(address, extraction.get().value()));
    }
    return List.copyOf(entries);
  }

  /**
   * A single interrogated point value: a {@link PointAddress} paired with its {@link PointValue}.
   *
   * @param address the fully qualified address of the point.
   * @param value the value reported for the point.
   */
  public record PointEntry(PointAddress address, PointValue<?> value) {

    /**
     * Validates the components of the entry.
     *
     * @param address the fully qualified address of the point.
     * @param value the value reported for the point.
     * @throws NullPointerException if {@code address} or {@code value} is null.
     */
    public PointEntry {
      Objects.requireNonNull(address, "address");
      Objects.requireNonNull(value, "value");
    }
  }
}
