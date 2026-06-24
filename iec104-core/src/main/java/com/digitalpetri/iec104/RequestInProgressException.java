package com.digitalpetri.iec104;

/**
 * Thrown when a request is issued while a conflicting request to the same target is still in
 * flight.
 *
 * <p>The client serializes requests whose responses could not be told apart on the wire, so the
 * caller should wait for the in-flight request to complete before retrying, or address a different
 * target.
 */
public class RequestInProgressException extends Iec104Exception {

  /**
   * Creates an exception with the given detail message.
   *
   * @param message the detail message describing which request is already in flight.
   */
  public RequestInProgressException(String message) {
    super(message);
  }
}
