package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.AsduDecodeException;

/**
 * Thrown by a frame decoder when the inbound byte stream cannot be framed, carrying a copy of the
 * offending octets so the inbound handler can report them before the connection closes.
 */
final class FramingException extends AsduDecodeException {

  /** The most octets copied: one maximum-length IEC 60870-5-104 frame. */
  static final int MAX_OCTETS = 2 + 255;

  private final byte[] octets;

  /**
   * Creates the exception.
   *
   * @param message the detail message.
   * @param octets the offending octets, starting at the octet that could not be framed.
   */
  FramingException(String message, byte[] octets) {
    super(message);
    this.octets = octets.clone();
  }

  /**
   * Returns the offending octets.
   *
   * @return a copy of the octets.
   */
  byte[] octets() {
    return octets.clone();
  }
}
