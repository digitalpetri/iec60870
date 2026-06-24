package com.digitalpetri.iec60870;

/**
 * Thrown when an expected protocol response does not arrive within the configured timeout window.
 *
 * <p>This typically surfaces when an APCI timer ({@code t1}-{@code t3}) or a request/response
 * deadline elapses before the peer responds, for example a missing {@code TESTFR} confirmation or a
 * command activation confirmation that never arrives.
 */
public class ProtocolTimeoutException extends Iec60870Exception {

  /**
   * Creates an exception with the given detail message.
   *
   * @param message the detail message describing which operation timed out.
   */
  public ProtocolTimeoutException(String message) {
    super(message);
  }

  /**
   * Creates an exception with the given detail message and cause.
   *
   * @param message the detail message describing which operation timed out.
   * @param cause the underlying cause of the failure.
   */
  public ProtocolTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
