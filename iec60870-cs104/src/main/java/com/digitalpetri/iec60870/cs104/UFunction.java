package com.digitalpetri.iec60870.cs104;

/**
 * The unnumbered control function carried by a U-format APDU control field.
 *
 * <p>Only one U-format function may be active at a time on a connection. Each {@code *_ACT} value
 * is an activation request issued by the controlling station, and the matching {@code *_CON} value
 * is the confirmation returned by the controlled station.
 *
 * <ul>
 *   <li>{@code STARTDT} enables user-data transfer from the controlled station; {@code STOPDT}
 *       disables it. After a connection is established the default state is stopped, so STARTDT
 *       must be sent before monitor-direction data flows.
 *   <li>{@code TESTFR} tests that an idle connection is still alive.
 * </ul>
 *
 * @see ControlField.TypeU
 */
public enum UFunction {

  /** STARTDT activation: request to start user-data transfer. */
  STARTDT_ACT,

  /** STARTDT confirmation: acknowledgement that user-data transfer has started. */
  STARTDT_CON,

  /** STOPDT activation: request to stop user-data transfer. */
  STOPDT_ACT,

  /** STOPDT confirmation: acknowledgement that user-data transfer has stopped. */
  STOPDT_CON,

  /** TESTFR activation: test-frame request used to probe an idle connection. */
  TESTFR_ACT,

  /** TESTFR confirmation: acknowledgement of a test-frame request. */
  TESTFR_CON
}
