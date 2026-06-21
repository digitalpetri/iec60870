package com.digitalpetri.iec104;

/**
 * Base type for all unchecked exceptions raised by the IEC 60870-5-104 library.
 *
 * <p>Catch this type to handle any library-specific failure uniformly. More specific subclasses
 * distinguish the failure mode (for example malformed wire data, a protocol timeout, or a negative
 * confirmation) and may carry additional context.
 */
public class Iec104Exception extends RuntimeException {

  /** Creates an exception with no detail message or cause. */
  public Iec104Exception() {
    super();
  }

  /**
   * Creates an exception with the given detail message.
   *
   * @param message the detail message describing the failure.
   */
  public Iec104Exception(String message) {
    super(message);
  }

  /**
   * Creates an exception with the given detail message and cause.
   *
   * @param message the detail message describing the failure.
   * @param cause the underlying cause of the failure.
   */
  public Iec104Exception(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates an exception with the given cause.
   *
   * @param cause the underlying cause of the failure.
   */
  public Iec104Exception(Throwable cause) {
    super(cause);
  }
}
