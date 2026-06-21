package com.digitalpetri.iec104.asdu;

import com.digitalpetri.iec104.address.InformationObjectAddress;

/**
 * A single information object carried by an {@link Asdu}: an information object address paired with
 * the type-specific information elements that follow it on the wire.
 *
 * <p>Each in-scope type identification has a dedicated record in the {@code com.digitalpetri.iec104
 * .asdu.object} package implementing this interface. The information object address (IOA) framing
 * is handled centrally by {@link Asdu.Serde}; the per-type {@link InformationObjectCodec} only
 * encodes and decodes the elements that follow the IOA.
 */
public interface InformationObject {

  /**
   * Returns the information object address that identifies this object within its common address.
   *
   * @return the information object address.
   */
  InformationObjectAddress address();
}
