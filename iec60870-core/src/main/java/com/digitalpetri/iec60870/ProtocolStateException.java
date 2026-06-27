package com.digitalpetri.iec60870;

/**
 * Thrown when a peer sends a frame that is not permitted in the session's current protocol state.
 *
 * <p>Unlike {@link SequenceNumberException}, which reports a flow-control sequence-number
 * violation, this indicates that an otherwise well-formed frame arrived in a state where the state
 * machine does not allow it — for example an I-format APDU carrying user data received while data
 * transfer is stopped (before {@code STARTDT} or after {@code STOPDT}).
 */
public class ProtocolStateException extends Iec60870Exception {

  /**
   * Creates an exception with the given detail message.
   *
   * @param message the detail message describing the protocol-state violation.
   */
  public ProtocolStateException(String message) {
    super(message);
  }

  /**
   * Creates an exception with the given detail message and cause.
   *
   * @param message the detail message describing the protocol-state violation.
   * @param cause the underlying cause of the failure.
   */
  public ProtocolStateException(String message, Throwable cause) {
    super(message, cause);
  }
}
