package com.digitalpetri.iec104.asdu;

import com.digitalpetri.iec104.UnsupportedAsduTypeException;

/**
 * Resolves the {@link InformationObjectCodec} to use for a given {@link AsduType}.
 *
 * <p>{@link Asdu.Serde} consults a registry to encode and decode the information elements of each
 * object in an ASDU, after framing the information object address itself. A registry typically maps
 * every {@linkplain AsduType#supported() supported} type to its codec.
 */
public interface InformationObjectCodecRegistry {

  /**
   * Returns the codec registered for the given type identification.
   *
   * @param type the ASDU type whose codec is requested.
   * @param <T> the concrete information object type produced by the returned codec.
   * @return the codec registered for {@code type}.
   * @throws UnsupportedAsduTypeException if no codec is registered for {@code type}.
   */
  <T extends InformationObject> InformationObjectCodec<T> codecFor(AsduType type);
}
