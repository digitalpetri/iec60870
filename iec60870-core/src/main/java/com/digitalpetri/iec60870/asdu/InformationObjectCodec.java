package com.digitalpetri.iec60870.asdu;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import io.netty.buffer.ByteBuf;

/**
 * Encodes and decodes the type-specific information elements of a single {@link InformationObject},
 * excluding the information object address (IOA).
 *
 * <p>The IOA is framed centrally by {@link Asdu.Serde}: on encode the IOA is written before {@link
 * #encodeElements(InformationObject, ByteBuf)} is called, and on decode the IOA is read and passed
 * to {@link #decode(InformationObjectAddress, ByteBuf)}. Implementations therefore operate only on
 * the elements that follow the IOA.
 *
 * <p>All multi-octet element fields use Mode 1 (least significant octet first). Implementations
 * write into and read from a caller-owned buffer and never allocate or release it.
 *
 * @param <T> the concrete information object type handled by this codec.
 */
public interface InformationObjectCodec<T extends InformationObject> {

  /**
   * Encodes the information elements of {@code object} into {@code buffer}, starting at its current
   * writer index.
   *
   * <p>Does not write the information object address and does not release the buffer.
   *
   * @param object the information object whose elements are to be encoded.
   * @param buffer the destination buffer to write the elements into.
   */
  void encodeElements(T object, ByteBuf buffer);

  /**
   * Decodes the information elements of a single object from {@code buffer}, starting at its
   * current reader index, combining them with the already-read information object address.
   *
   * <p>The information object address has already been read by {@link Asdu.Serde}. Does not release
   * the buffer.
   *
   * @param address the information object address already read for this object.
   * @param buffer the source buffer to read the elements from.
   * @return the decoded information object.
   * @throws com.digitalpetri.iec60870.AsduDecodeException if the buffer contains malformed element
   *     data.
   */
  T decode(InformationObjectAddress address, ByteBuf buffer);
}
