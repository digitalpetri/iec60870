package com.digitalpetri.iec104;

/**
 * Thrown when an APCI send or receive sequence number violates the protocol's flow-control rules.
 *
 * <p>This indicates a sequence-number mismatch detected by the {@code k}/{@code w} window state
 * machine, such as an acknowledgement that does not correspond to an outstanding I-format frame, or
 * a received sequence number that skips expected values.
 */
public class SequenceNumberException extends Iec104Exception {

  /**
   * Creates an exception with the given detail message.
   *
   * @param message the detail message describing the sequence-number violation.
   */
  public SequenceNumberException(String message) {
    super(message);
  }

  /**
   * Creates an exception with the given detail message and cause.
   *
   * @param message the detail message describing the sequence-number violation.
   * @param cause the underlying cause of the failure.
   */
  public SequenceNumberException(String message, Throwable cause) {
    super(message, cause);
  }
}
