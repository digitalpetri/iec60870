package com.digitalpetri.iec104.point;

/**
 * A way in which a point may be interacted with.
 *
 * <p>A point may declare any combination of these capabilities; they describe what operations are
 * meaningful against the point rather than constraining a single value.
 */
public enum PointCapability {

  /**
   * The point's current value can be read on demand (for example via a read command, C_RD_NA_1).
   */
  READABLE,

  /**
   * The point can be controlled by a command in the control direction (for example a single or
   * double command, or a set-point command).
   */
  COMMANDABLE,

  /**
   * The point is reported in the monitor direction, either spontaneously or in response to an
   * interrogation, as a monitor information object.
   */
  REPORTED
}
