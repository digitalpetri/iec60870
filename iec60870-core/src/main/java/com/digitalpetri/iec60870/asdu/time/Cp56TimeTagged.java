package com.digitalpetri.iec60870.asdu.time;

/**
 * An information object that carries a CP56Time2a time tag.
 *
 * <p>Every standard record with a seven-octet time tag implements this interface: the
 * CP56Time2a-tagged monitor types, the protection-equipment event types, the time-tagged commands,
 * clock synchronization, and the time-tagged test command. Code that only needs the time tag can
 * read it without testing for each concrete record:
 *
 * <pre>{@code
 * if (object instanceof Cp56TimeTagged tagged) {
 *   Cp56Time2a time = tagged.time();
 * }
 * }</pre>
 */
public interface Cp56TimeTagged {

  /**
   * Returns the time tag exactly as carried on the wire.
   *
   * @return the CP56Time2a time tag.
   */
  Cp56Time2a time();
}
