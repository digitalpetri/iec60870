package com.digitalpetri.iec104.codec;

import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.InformationObjectCodec;
import java.util.Optional;

/**
 * A registry that maps an {@link AsduType} to the {@link InformationObjectCodec} used to encode and
 * decode that type's information elements.
 *
 * <p>This is the raw-layer extensibility seam for private or uncommon TypeIDs that the library does
 * not model with a typed {@code com.digitalpetri.iec104.asdu.object} record. The standard, built-in
 * codecs for the supported TypeIDs live elsewhere (in {@code
 * com.digitalpetri.iec104.asdu.InformationObjectCodecs}, added during the integration phase);
 * register codecs here only to add coverage for TypeIDs the built-in tables do not handle.
 *
 * <p>Implementations may be consulted concurrently during encode/decode dispatch. The default
 * implementation {@link MutableTypeCodecRegistry} is thread-safe.
 *
 * <p>Example:
 *
 * <pre>{@code
 * TypeCodecRegistry registry = new MutableTypeCodecRegistry();
 * registry.register(privateType, privateCodec);
 *
 * Optional<InformationObjectCodec<?>> codec = registry.find(privateType);
 * }</pre>
 */
public interface TypeCodecRegistry {

  /**
   * Registers the codec used to encode and decode information objects of the given type.
   *
   * <p>Registering a type that already has a codec replaces the previous registration.
   *
   * @param type the ASDU type the codec handles.
   * @param codec the codec for the type's information elements.
   */
  void register(AsduType type, InformationObjectCodec<?> codec);

  /**
   * Looks up the codec registered for the given type.
   *
   * @param type the ASDU type to resolve.
   * @return the registered codec, or an empty {@link Optional} if no codec is registered for the
   *     type.
   */
  Optional<InformationObjectCodec<?>> find(AsduType type);
}
