package com.digitalpetri.iec104;

/**
 * Thrown when an operation cannot complete because the underlying connection is closed or was lost.
 *
 * <p>Pending requests are failed with this exception when the transport reports a connection loss,
 * and new operations attempted on a closed connection fail with it as well.
 */
public class ConnectionClosedException extends Iec104Exception {

  /** Creates an exception with no detail message or cause. */
  public ConnectionClosedException() {
    super();
  }

  /**
   * Creates an exception with the given detail message.
   *
   * @param message the detail message describing the closure.
   */
  public ConnectionClosedException(String message) {
    super(message);
  }

  /**
   * Creates an exception with the given detail message and cause.
   *
   * @param message the detail message describing the closure.
   * @param cause the underlying cause of the closure.
   */
  public ConnectionClosedException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates an exception with the given cause.
   *
   * @param cause the underlying cause of the closure.
   */
  public ConnectionClosedException(Throwable cause) {
    super(cause);
  }
}
