package com.digitalpetri.iec104;

/**
 * Thrown when an ASDU type identification is not recognized, or is recognized but has no typed
 * object mapping in this library.
 *
 * <p>This surfaces when decoding encounters a type identification that is undefined in the protocol
 * tables, or when an operation requires a typed object record for a type that is only reachable
 * through the raw codec extension point.
 */
public class UnsupportedAsduTypeException extends Iec104Exception {

  /**
   * Creates an exception with the given detail message.
   *
   * @param message the detail message naming the unsupported type identification.
   */
  public UnsupportedAsduTypeException(String message) {
    super(message);
  }

  /**
   * Creates an exception with the given detail message and cause.
   *
   * @param message the detail message naming the unsupported type identification.
   * @param cause the underlying cause of the failure.
   */
  public UnsupportedAsduTypeException(String message, Throwable cause) {
    super(message, cause);
  }
}
