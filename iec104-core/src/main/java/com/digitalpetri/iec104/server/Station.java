package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.PointValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * A single controlled station hosted by an {@link Iec104Server}, identified by its {@link
 * CommonAddress}.
 *
 * <p>A station owns a set of {@link PointDefinition PointDefinitions} and a thread-safe
 * current-value image keyed by {@link InformationObjectAddress}. The server reads the image to
 * answer interrogations and reads, and writes it as values are {@linkplain Iec104Server#publish
 * published} or commands are accepted. Each point is assigned to zero or more interrogation groups
 * (1..16); a station (global) interrogation reports every reported point, while a group
 * interrogation reports only the points assigned to that group.
 *
 * <p>Build a station with {@link #builder(CommonAddress)}:
 *
 * <pre>{@code
 * Station station = Station.builder(CommonAddress.of(1))
 *     .point(PointDefinition.of(PointAddress.of(1, 100), PointType.SINGLE_POINT,
 *         PointValue.single(false), PointCapability.REPORTED))
 *     .group(1, PointAddress.of(1, 100))
 *     .build();
 * }</pre>
 *
 * <p>A station instance is thread-safe: its value image may be read and written concurrently.
 */
public final class Station {

  private final CommonAddress commonAddress;
  private final Map<InformationObjectAddress, PointDefinition<?>> definitions;
  private final Map<InformationObjectAddress, Set<Integer>> groups;
  private final Map<InformationObjectAddress, Set<Integer>> counterGroups;
  private final Map<InformationObjectAddress, PointValue<?>> image = new ConcurrentHashMap<>();

  private Station(
      CommonAddress commonAddress,
      Map<InformationObjectAddress, PointDefinition<?>> definitions,
      Map<InformationObjectAddress, Set<Integer>> groups,
      Map<InformationObjectAddress, Set<Integer>> counterGroups) {

    this.commonAddress = commonAddress;
    this.definitions = definitions;
    this.groups = groups;
    this.counterGroups = counterGroups;
    definitions.forEach((ioa, definition) -> image.put(ioa, definition.initialValue()));
  }

  /**
   * Returns the common address that identifies this station.
   *
   * @return the station's common address.
   */
  public CommonAddress commonAddress() {
    return commonAddress;
  }

  /**
   * Returns the definitions of every point hosted by this station.
   *
   * @return an unmodifiable list of point definitions, in declaration order.
   */
  public List<PointDefinition<?>> pointDefinitions() {
    return List.copyOf(definitions.values());
  }

  /**
   * Returns the definition of the point at the given information object address, if this station
   * hosts it.
   *
   * @param address the information object address.
   * @return the point definition, or an empty {@link Optional} if no such point is defined.
   */
  public Optional<PointDefinition<?>> definition(InformationObjectAddress address) {
    return Optional.ofNullable(definitions.get(address));
  }

  /**
   * Returns the current value held in the image for the point at the given information object
   * address.
   *
   * @param address the information object address.
   * @return the current point value, or an empty {@link Optional} if no such point is defined.
   */
  public Optional<PointValue<?>> currentValue(InformationObjectAddress address) {
    return Optional.ofNullable(image.get(address));
  }

  /**
   * Updates the current value held in the image for the point at the given information object
   * address.
   *
   * <p>Has no effect if this station does not host a point at {@code address}.
   *
   * @param address the information object address.
   * @param value the new value.
   * @throws NullPointerException if {@code address} or {@code value} is null.
   */
  public void updateValue(InformationObjectAddress address, PointValue<?> value) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(value, "value");
    if (definitions.containsKey(address)) {
      image.put(address, value);
    }
  }

  /**
   * Returns the points of this station selected by the given qualifier of interrogation.
   *
   * <p>A {@linkplain QualifierOfInterrogation#STATION station} qualifier selects every {@linkplain
   * PointCapability#REPORTED reported} point. A group qualifier ({@code GROUP_1}..{@code GROUP_16})
   * selects only the reported points assigned to that group.
   *
   * @param qoi the qualifier of interrogation.
   * @return the matching points, each paired with its current value, in declaration order.
   */
  public List<InterrogatedPoint> select(QualifierOfInterrogation qoi) {
    int value = qoi.value().intValue();
    boolean stationWide = value == QualifierOfInterrogation.STATION.value().intValue();
    int group =
        stationWide ? -1 : (value - QualifierOfInterrogation.GROUP_1.value().intValue() + 1);

    List<InterrogatedPoint> selected = new ArrayList<>();
    for (PointDefinition<?> definition : definitions.values()) {
      if (!definition.hasCapability(PointCapability.REPORTED)) {
        continue;
      }
      InformationObjectAddress ioa = definition.address().objectAddress();
      if (!stationWide && !groups.getOrDefault(ioa, Set.of()).contains(group)) {
        continue;
      }
      PointValue<?> current = image.get(ioa);
      if (current != null) {
        selected.add(new InterrogatedPoint(definition, current));
      }
    }
    return List.copyOf(selected);
  }

  /**
   * Returns the integrated-totals (counter) points of this station selected by a counter group.
   *
   * <p>A {@code group} of {@code 1..4} selects only the {@linkplain PointCapability#REPORTED
   * reported} integrated-totals points assigned to that counter group (a separate {@code 1..4}
   * namespace from the interrogation groups). A {@code group} of {@code 0} or less is a general
   * counter request and selects every reported integrated-totals point.
   *
   * @param group the counter group number ({@code 1..4}), or {@code 0} or less for a general
   *     request.
   * @return the matching integrated-totals points, each paired with its current value, in
   *     declaration order.
   */
  public List<InterrogatedPoint> selectCounterGroup(int group) {
    boolean general = group < 1;
    List<InterrogatedPoint> selected = new ArrayList<>();
    for (PointDefinition<?> definition : definitions.values()) {
      if (definition.type() != PointType.INTEGRATED_TOTALS) {
        continue;
      }
      if (!definition.hasCapability(PointCapability.REPORTED)) {
        continue;
      }
      InformationObjectAddress ioa = definition.address().objectAddress();
      if (!general && !counterGroups.getOrDefault(ioa, Set.of()).contains(group)) {
        continue;
      }
      PointValue<?> current = image.get(ioa);
      if (current != null) {
        selected.add(new InterrogatedPoint(definition, current));
      }
    }
    return List.copyOf(selected);
  }

  /**
   * A point selected by an interrogation: its definition paired with its current value.
   *
   * @param definition the point definition.
   * @param value the current value held in the station image.
   */
  public record InterrogatedPoint(PointDefinition<?> definition, PointValue<?> value) {

    /**
     * Validates the components of the selected point.
     *
     * @param definition the point definition.
     * @param value the current value held in the station image.
     * @throws NullPointerException if {@code definition} or {@code value} is null.
     */
    public InterrogatedPoint {
      Objects.requireNonNull(definition, "definition");
      Objects.requireNonNull(value, "value");
    }

    /**
     * Returns the point's logical type.
     *
     * @return the point type.
     */
    public PointType type() {
      return definition.type();
    }

    /**
     * Returns the point's information object address.
     *
     * @return the information object address.
     */
    public InformationObjectAddress address() {
      return definition.address().objectAddress();
    }
  }

  /**
   * Returns a builder for a station with the given common address.
   *
   * @param commonAddress the common address that identifies the station.
   * @return a new builder.
   * @throws NullPointerException if {@code commonAddress} is null.
   */
  public static Builder builder(CommonAddress commonAddress) {
    return new Builder(Objects.requireNonNull(commonAddress, "commonAddress"));
  }

  /**
   * A builder for a {@link Station}.
   *
   * <p>Add points with {@link #point(PointDefinition)} and assign them to interrogation groups with
   * {@link #group(int, PointAddress)}. The builder is not thread-safe; build a station on one
   * thread and share the immutable, thread-safe result.
   */
  public static final class Builder {

    private final CommonAddress commonAddress;
    private final Map<InformationObjectAddress, PointDefinition<?>> definitions =
        new LinkedHashMap<>();
    private final Map<InformationObjectAddress, Set<Integer>> groups = new LinkedHashMap<>();
    private final Map<InformationObjectAddress, Set<Integer>> counterGroups = new LinkedHashMap<>();

    private Builder(CommonAddress commonAddress) {
      this.commonAddress = commonAddress;
    }

    /**
     * Adds a point to the station.
     *
     * <p>The point's {@link PointAddress#commonAddress()} must equal the station's common address.
     *
     * @param definition the point definition.
     * @return this builder.
     * @throws NullPointerException if {@code definition} is null.
     * @throws IllegalArgumentException if the point belongs to a different common address or
     *     duplicates an already-added information object address.
     */
    public Builder point(PointDefinition<?> definition) {
      Objects.requireNonNull(definition, "definition");
      PointAddress address = definition.address();
      if (!address.commonAddress().equals(commonAddress)) {
        throw new IllegalArgumentException(
            "point common address " + address.commonAddress() + " != station " + commonAddress);
      }
      InformationObjectAddress ioa = address.objectAddress();
      if (definitions.putIfAbsent(ioa, definition) != null) {
        throw new IllegalArgumentException("duplicate information object address: " + ioa);
      }
      return this;
    }

    /**
     * Assigns a point to an interrogation group.
     *
     * <p>A point may belong to multiple groups; group interrogation of any assigned group reports
     * the point. The point must already have been added with {@link #point(PointDefinition)}.
     *
     * @param group the interrogation group number, in the range {@code 1..16}.
     * @param point the address of the point to assign.
     * @return this builder.
     * @throws IllegalArgumentException if {@code group} is outside {@code 1..16} or no point has
     *     been added at {@code point}.
     * @throws NullPointerException if {@code point} is null.
     */
    public Builder group(int group, PointAddress point) {
      Objects.requireNonNull(point, "point");
      if (group < 1 || group > 16) {
        throw new IllegalArgumentException("interrogation group must be in 1..16: " + group);
      }
      InformationObjectAddress ioa = point.objectAddress();
      if (!definitions.containsKey(ioa)) {
        throw new IllegalArgumentException("no point defined at " + point);
      }
      groups.computeIfAbsent(ioa, ignored -> new HashSet<>()).add(group);
      return this;
    }

    /**
     * Assigns an integrated-totals point to a counter group.
     *
     * <p>Counter groups occupy a separate {@code 1..4} namespace from the interrogation groups: a
     * counter interrogation of a given group reports the integrated-totals points assigned to that
     * counter group, while a general counter request reports every integrated-totals point. A point
     * may belong to multiple counter groups; it must already have been added with {@link
     * #point(PointDefinition)}.
     *
     * @param group the counter group number, in the range {@code 1..4}.
     * @param point the address of the integrated-totals point to assign.
     * @return this builder.
     * @throws IllegalArgumentException if {@code group} is outside {@code 1..4} or no point has
     *     been added at {@code point}.
     * @throws NullPointerException if {@code point} is null.
     */
    public Builder counterGroup(int group, PointAddress point) {
      Objects.requireNonNull(point, "point");
      if (group < 1 || group > 4) {
        throw new IllegalArgumentException("counter group must be in 1..4: " + group);
      }
      InformationObjectAddress ioa = point.objectAddress();
      if (!definitions.containsKey(ioa)) {
        throw new IllegalArgumentException("no point defined at " + point);
      }
      counterGroups.computeIfAbsent(ioa, ignored -> new HashSet<>()).add(group);
      return this;
    }

    /**
     * Builds an immutable {@link Station} from the current builder state.
     *
     * @return the station, seeded with each point's initial value.
     */
    public Station build() {
      Map<InformationObjectAddress, PointDefinition<?>> copiedDefinitions =
          new LinkedHashMap<>(definitions);
      Map<InformationObjectAddress, Set<Integer>> copiedGroups = new LinkedHashMap<>();
      groups.forEach((ioa, set) -> copiedGroups.put(ioa, Set.copyOf(set)));
      Map<InformationObjectAddress, Set<Integer>> copiedCounterGroups = new LinkedHashMap<>();
      counterGroups.forEach((ioa, set) -> copiedCounterGroups.put(ioa, Set.copyOf(set)));
      return new Station(commonAddress, copiedDefinitions, copiedGroups, copiedCounterGroups);
    }
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    return this == obj;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public String toString() {
    return "Station{commonAddress=" + commonAddress + ", points=" + definitions.size() + "}";
  }
}
