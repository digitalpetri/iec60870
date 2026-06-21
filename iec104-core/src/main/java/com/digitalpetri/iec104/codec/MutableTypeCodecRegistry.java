package com.digitalpetri.iec104.codec;

import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.InformationObjectCodec;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe, mutable {@link TypeCodecRegistry} that accumulates codec registrations for private
 * or uncommon TypeIDs.
 *
 * <p>Typical use is to register codecs once at startup and consult them throughout the lifetime of
 * a connection or session. Registrations and lookups may safely happen concurrently; a lookup
 * observes either the previous registration for a type or a newer one, never a partially-applied
 * state.
 *
 * <p>The standard, built-in codecs for the supported TypeIDs are defined elsewhere (in {@code
 * com.digitalpetri.iec104.asdu.InformationObjectCodecs}); this registry is for user extensions.
 */
public final class MutableTypeCodecRegistry implements TypeCodecRegistry {

  private final Map<AsduType, InformationObjectCodec<?>> codecs = new ConcurrentHashMap<>();

  /** Creates an empty registry with no codec registrations. */
  public MutableTypeCodecRegistry() {}

  /**
   * Registers the codec used to encode and decode information objects of the given type, replacing
   * any codec previously registered for that type.
   *
   * @param type the ASDU type the codec handles.
   * @param codec the codec for the type's information elements.
   */
  @Override
  public void register(AsduType type, InformationObjectCodec<?> codec) {
    codecs.put(type, codec);
  }

  /**
   * Looks up the codec registered for the given type.
   *
   * @param type the ASDU type to resolve.
   * @return the registered codec, or an empty {@link Optional} if no codec is registered for the
   *     type.
   */
  @Override
  public Optional<InformationObjectCodec<?>> find(AsduType type) {
    return Optional.ofNullable(codecs.get(type));
  }
}
