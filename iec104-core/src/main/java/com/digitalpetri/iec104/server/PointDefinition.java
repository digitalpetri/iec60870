package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.PointValue;
import java.util.EnumSet;
import java.util.Objects;

/**
 * The definition of a single point hosted by a {@link Station}: its address, logical type, initial
 * value, and capabilities.
 *
 * <p>A definition is the static description of a point; its live value lives in the owning
 * station's current-value image and changes as the server {@linkplain Iec104Server#publish
 * publishes} updates or accepts commands. The {@code initialValue} seeds that image when the
 * station is created, and the {@code capabilities} describe which operations are meaningful against
 * the point (whether it is {@linkplain PointCapability#READABLE readable}, {@linkplain
 * PointCapability#COMMANDABLE commandable}, and/or {@linkplain PointCapability#REPORTED reported}
 * in the monitor direction).
 *
 * <pre>{@code
 * PointDefinition<Boolean> breaker = PointDefinition.of(
 *     PointAddress.of(1, 100),
 *     PointType.SINGLE_POINT,
 *     PointValue.single(false),
 *     PointCapability.REPORTED, PointCapability.COMMANDABLE);
 * }</pre>
 *
 * @param <T> the natural Java type of the point's value (see {@link PointValue}).
 * @param address the fully qualified address of the point.
 * @param type the logical point type, which selects the monitor type family used to report it.
 * @param initialValue the value used to seed the station's image; its runtime type must match
 *     {@code type}.
 * @param capabilities the operations meaningful against the point.
 */
public record PointDefinition<T>(
    PointAddress address,
    PointType type,
    PointValue<T> initialValue,
    EnumSet<PointCapability> capabilities) {

  /**
   * Validates the definition and defensively copies the capability set.
   *
   * @param address the fully qualified address of the point.
   * @param type the logical point type, which selects the monitor type family used to report it.
   * @param initialValue the value used to seed the station's image; its runtime type must match
   *     {@code type}.
   * @param capabilities the operations meaningful against the point.
   * @throws NullPointerException if {@code address}, {@code type}, {@code initialValue}, or {@code
   *     capabilities} is null.
   * @throws IllegalArgumentException if {@code initialValue}'s type does not match {@code type}.
   */
  public PointDefinition {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(initialValue, "initialValue");
    Objects.requireNonNull(capabilities, "capabilities");
    if (initialValue.type() != type) {
      throw new IllegalArgumentException(
          "initialValue type " + initialValue.type() + " does not match point type " + type);
    }
    capabilities = EnumSet.copyOf(capabilities);
  }

  /**
   * Creates a point definition with the given capabilities.
   *
   * @param address the fully qualified address of the point.
   * @param type the logical point type.
   * @param initialValue the value used to seed the station's image.
   * @param capabilities the operations meaningful against the point.
   * @param <T> the natural Java type of the point's value.
   * @return the point definition.
   * @throws NullPointerException if any argument is null.
   */
  public static <T> PointDefinition<T> of(
      PointAddress address,
      PointType type,
      PointValue<T> initialValue,
      PointCapability... capabilities) {

    Objects.requireNonNull(capabilities, "capabilities");
    EnumSet<PointCapability> set = EnumSet.noneOf(PointCapability.class);
    for (PointCapability capability : capabilities) {
      set.add(Objects.requireNonNull(capability, "capability"));
    }
    return new PointDefinition<>(address, type, initialValue, set);
  }

  /**
   * Reports whether this point declares the given capability.
   *
   * @param capability the capability to test.
   * @return {@code true} if the point declares {@code capability}.
   */
  // Public API accessor; reads naturally in the positive even though internal callers negate it.
  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  public boolean hasCapability(PointCapability capability) {
    return capabilities.contains(capability);
  }

  /**
   * Returns the declared capabilities as an independent copy that the caller may modify freely.
   *
   * @return a copy of the point's capability set.
   */
  @Override
  public EnumSet<PointCapability> capabilities() {
    return capabilities.isEmpty()
        ? EnumSet.noneOf(PointCapability.class)
        : EnumSet.copyOf(capabilities);
  }
}
