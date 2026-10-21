package com.digitalpetri.iec60870.cs104;

/**
 * The IEC 60870-5-104 APCI timers an {@link ApciSession} reports through {@link ApciDiagnostics}.
 */
public enum ApciTimer {

  /**
   * The acknowledgement timeout: a sent I-frame or U-frame activation was not acknowledged, or
   * inbound data stayed paused for {@code t1}. An expiry closes the session.
   */
  T1,

  /** The acknowledgement delay: received I-frames are acknowledged with an S-frame. */
  T2,

  /** The idle timeout: the session sends a TESTFR act to probe an idle connection. */
  T3
}
