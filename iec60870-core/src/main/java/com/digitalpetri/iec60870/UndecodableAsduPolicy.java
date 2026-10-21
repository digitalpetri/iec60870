package com.digitalpetri.iec60870;

/**
 * What a link layer does with a correctly framed frame whose ASDU cannot be decoded.
 *
 * <p>An ASDU can fail to decode even though its frame is intact: an undefined or private type
 * identification, a file-transfer type with no codec, an undefined cause of transmission, a count
 * that does not match the octets present, or an element the codec rejects. The frame boundary and
 * the link control information are still trustworthy in each case, so the link layer can accept the
 * frame without losing sync. Framing errors (a bad start octet, length, checksum, or control field,
 * and sequence-number violations) are not covered by this policy; they always follow the link
 * layer's own error handling.
 */
public enum UndecodableAsduPolicy {

  /**
   * Accept the frame at the link layer, report the raw ASDU octets and the decode failure through
   * {@link com.digitalpetri.iec60870.session.Session.Events#onUndecodableAsdu(byte[],
   * Iec60870Exception)}, and keep the connection open.
   *
   * <p>On CS 104 the I-frame advances V(R) and is acknowledged like any other. On CS 101 the frame
   * is acknowledged and its frame count bit advances like any other user-data frame.
   */
  REPORT_AND_CONTINUE,

  /**
   * Treat the frame as a link error and do not accept it.
   *
   * <p>On CS 104 the connection closes without acknowledging the I-frame, and the decode failure is
   * reported as the cause of the connection loss. On CS 101 the frame is discarded without an
   * acknowledgement, as a frame with a bad checksum would be. This mode is intended for conformance
   * testing.
   */
  STRICT;

  /** The default policy used when a configuration does not specify one. */
  public static final UndecodableAsduPolicy DEFAULT = REPORT_AND_CONTINUE;
}
