package com.digitalpetri.iec60870;

/**
 * Thrown when an ASDU or one of its fields cannot be decoded from the wire because the octet stream
 * is malformed, truncated, or carries a value outside the range the protocol allows.
 *
 * <p>Decoders throw this exception (rather than {@link IllegalArgumentException}) so callers can
 * distinguish a bad peer message from a programming error in locally supplied arguments.
 */
public class AsduDecodeException extends Iec60870Exception {

  /**
   * Creates an exception with the given detail message.
   *
   * @param message the detail message describing why decoding failed.
   */
  public AsduDecodeException(String message) {
    super(message);
  }

  /**
   * Creates an exception with the given detail message and cause.
   *
   * @param message the detail message describing why decoding failed.
   * @param cause the underlying cause of the failure.
   */
  public AsduDecodeException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates an exception with the given cause.
   *
   * @param cause the underlying cause of the failure.
   */
  public AsduDecodeException(Throwable cause) {
    super(cause);
  }
}
