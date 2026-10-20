package com.digitalpetri.iec60870.asdu.time;

/**
 * An information object that carries a CP24Time2a time tag.
 *
 * <p>Every standard record with a three-octet time tag implements this interface: the
 * CP24Time2a-tagged monitor types and the protection-equipment event types. These types belong to
 * IEC 60870-5-101; IEC 60870-5-104 uses only their CP56Time2a counterparts.
 *
 * @see Cp56TimeTagged
 */
public interface Cp24TimeTagged {

  /**
   * Returns the time tag exactly as carried on the wire.
   *
   * @return the CP24Time2a time tag.
   */
  Cp24Time2a time();
}
