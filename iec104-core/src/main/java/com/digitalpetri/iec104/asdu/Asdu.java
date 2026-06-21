package com.digitalpetri.iec104.asdu;

import com.digitalpetri.iec104.AsduDecodeException;
import com.digitalpetri.iec104.ProtocolProfile;
import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.address.OriginatorAddress;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;

/**
 * An Application Service Data Unit (ASDU): the data unit identifier together with the information
 * objects it conveys.
 *
 * <p>The data unit identifier comprises the {@linkplain #type() type identification}, the variable
 * structure qualifier (the {@linkplain #sequence() sequence flag} plus an implied object count),
 * the cause of transmission (the {@linkplain #cause() cause}, the {@linkplain #test() test} bit,
 * and the {@linkplain #negative() positive/negative} bit), the optional {@linkplain
 * #originatorAddress() originator address}, and the {@linkplain #commonAddress() common address}.
 * The {@linkplain #objects() objects} list carries the typed information objects.
 *
 * <p>Instances are immutable; the objects list is defensively copied on construction and exposed as
 * an unmodifiable list.
 *
 * @param type the type identification of every information object in this ASDU.
 * @param sequence the variable structure qualifier SQ bit; {@code true} selects sequence addressing
 *     (one address with consecutive offsets), {@code false} selects individual addressing.
 * @param cause the cause of transmission.
 * @param negative the positive/negative (P/N) bit; {@code true} indicates a negative confirmation.
 * @param test the test (T) bit; {@code true} indicates the ASDU was generated under test
 *     conditions.
 * @param originatorAddress the originator address; significant only when the active profile uses a
 *     two-octet cause of transmission.
 * @param commonAddress the common address of the ASDU.
 * @param objects the information objects carried by this ASDU.
 */
public record Asdu(
    AsduType type,
    boolean sequence,
    Cause cause,
    boolean negative,
    boolean test,
    OriginatorAddress originatorAddress,
    CommonAddress commonAddress,
    List<InformationObject> objects) {

  /** The maximum number of information objects or element groups a single ASDU may carry. */
  private static final int MAX_OBJECT_COUNT = 127;

  /**
   * Validates the components.
   *
   * @param type the type identification of every information object in this ASDU.
   * @param sequence the variable structure qualifier SQ bit; {@code true} selects sequence
   *     addressing (one address with consecutive offsets), {@code false} selects individual
   *     addressing.
   * @param cause the cause of transmission.
   * @param negative the positive/negative (P/N) bit; {@code true} indicates a negative
   *     confirmation.
   * @param test the test (T) bit; {@code true} indicates the ASDU was generated under test
   *     conditions.
   * @param originatorAddress the originator address; significant only when the active profile uses
   *     a two-octet cause of transmission.
   * @param commonAddress the common address of the ASDU.
   * @param objects the information objects carried by this ASDU.
   * @throws IllegalArgumentException if the objects list contains more than 127 entries.
   */
  public Asdu {
    if (objects.size() > MAX_OBJECT_COUNT) {
      throw new IllegalArgumentException("objects count out of range (0..127): " + objects.size());
    }
    objects = List.copyOf(objects);
  }

  /**
   * Encodes and decodes a complete ASDU (the data unit identifier and its information objects)
   * using a {@link ProtocolProfile} for field widths and an {@link InformationObjectCodecRegistry}
   * for the per-type information elements.
   *
   * <p>Wire layout (Mode 1, least significant octet first):
   *
   * <ol>
   *   <li>octet 1: type identification;
   *   <li>octet 2: variable structure qualifier {@code = (SQ ? 0x80 : 0) | (count & 0x7F)};
   *   <li>octet 3: cause of transmission {@code = (T ? 0x80 : 0) | (P/N ? 0x40 : 0) | (cause &
   *       0x3F)};
   *   <li>octet 4 (only when {@code profile.cotLength() == 2}): originator address;
   *   <li>common address: {@code profile.commonAddressLength()} octets;
   *   <li>information objects.
   * </ol>
   *
   * <p>For individual addressing (SQ = 0) each object is written as its information object address
   * ({@code profile.ioaLength()} octets) followed by its elements. For sequence addressing (SQ = 1)
   * the first object's information object address is written once, followed by each object's
   * elements in order; the addresses must be consecutive and ascending. On decode with SQ = 1 a
   * single information object address is read and {@code count} element groups follow, assigned the
   * addresses {@code ioa, ioa + 1, ...}.
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Returns the standard codec registry used by the convenience overloads that omit an explicit
     * {@link InformationObjectCodecRegistry}.
     *
     * <p>The {@linkplain InformationObjectCodecs#standard() standard registry} resolves a codec for
     * every {@linkplain AsduType#supported() supported} type identification by delegating to the
     * per-type record {@code Serde}. It is looked up lazily here so that class initialization of
     * {@code Asdu} does not depend on {@code InformationObjectCodecs} (which in turn references the
     * object records), avoiding any static-initialization cycle.
     *
     * @return the shared standard codec registry.
     */
    private static InformationObjectCodecRegistry standardRegistry() {
      return InformationObjectCodecs.standard();
    }

    /**
     * Encodes {@code asdu} into {@code buffer} using the {@linkplain
     * InformationObjectCodecs#standard() standard} codec registry.
     *
     * <p>This overload matches the pinned foundation signature and resolves the per-type
     * information element codec from the standard registry, so it handles every {@linkplain
     * AsduType#supported() supported} type identification.
     *
     * @param asdu the ASDU to encode.
     * @param profile the protocol profile selecting the field widths.
     * @param buffer the destination buffer to write into.
     * @throws com.digitalpetri.iec104.UnsupportedAsduTypeException if the ASDU's type
     *     identification has no registered codec.
     */
    public static void encode(Asdu asdu, ProtocolProfile profile, ByteBuf buffer) {
      encode(asdu, profile, standardRegistry(), buffer);
    }

    /**
     * Decodes a complete ASDU from {@code buffer} using the {@linkplain
     * InformationObjectCodecs#standard() standard} codec registry.
     *
     * <p>This overload matches the pinned foundation signature and resolves the per-type
     * information element codec from the standard registry, so it handles every {@linkplain
     * AsduType#supported() supported} type identification.
     *
     * @param profile the protocol profile selecting the field widths.
     * @param buffer the source buffer to read from.
     * @return the decoded ASDU.
     * @throws AsduDecodeException if the buffer ends before a fixed envelope field is fully read.
     * @throws com.digitalpetri.iec104.UnsupportedAsduTypeException if the type identification is
     *     undefined or has no registered codec.
     */
    public static Asdu decode(ProtocolProfile profile, ByteBuf buffer) {
      return decode(profile, standardRegistry(), buffer);
    }

    /**
     * Encodes {@code asdu} into {@code buffer} starting at its current writer index.
     *
     * <p>Does not release the buffer.
     *
     * @param asdu the ASDU to encode.
     * @param profile the protocol profile selecting the cause-of-transmission, common-address, and
     *     information-object-address field widths.
     * @param registry the registry supplying the per-type information element codec.
     * @param buffer the destination buffer to write into.
     * @throws IllegalArgumentException if, under sequence addressing, the objects do not have
     *     consecutive ascending information object addresses.
     */
    public static void encode(
        Asdu asdu,
        ProtocolProfile profile,
        InformationObjectCodecRegistry registry,
        ByteBuf buffer) {

      List<InformationObject> objects = asdu.objects();
      int count = objects.size();

      buffer.writeByte(asdu.type().typeId());
      buffer.writeByte((asdu.sequence() ? 0x80 : 0) | (count & 0x7F));
      buffer.writeByte(
          (asdu.test() ? 0x80 : 0) | (asdu.negative() ? 0x40 : 0) | (asdu.cause().value() & 0x3F));
      if (profile.cotLength() == 2) {
        buffer.writeByte(asdu.originatorAddress().value().intValue() & 0xFF);
      }
      CommonAddress.Serde.encode(asdu.commonAddress(), profile.commonAddressLength(), buffer);

      InformationObjectCodec<InformationObject> codec = registry.codecFor(asdu.type());

      if (asdu.sequence()) {
        if (!objects.isEmpty()) {
          long base = objects.get(0).address().value().longValue();
          for (int i = 0; i < count; i++) {
            long expected = base + i;
            long actual = objects.get(i).address().value().longValue();
            if (actual != expected) {
              throw new IllegalArgumentException(
                  "sequence addressing requires consecutive ascending information object "
                      + "addresses; expected "
                      + expected
                      + " but found "
                      + actual
                      + " at index "
                      + i);
            }
          }
          InformationObjectAddress.Serde.encode(
              objects.get(0).address(), profile.ioaLength(), buffer);
          for (InformationObject object : objects) {
            codec.encodeElements(object, buffer);
          }
        }
      } else {
        for (InformationObject object : objects) {
          InformationObjectAddress.Serde.encode(object.address(), profile.ioaLength(), buffer);
          codec.encodeElements(object, buffer);
        }
      }
    }

    /**
     * Decodes a complete ASDU from {@code buffer} starting at its current reader index.
     *
     * <p>Does not release the buffer.
     *
     * @param profile the protocol profile selecting the cause-of-transmission, common-address, and
     *     information-object-address field widths.
     * @param registry the registry supplying the per-type information element codec.
     * @param buffer the source buffer to read from.
     * @return the decoded ASDU.
     * @throws AsduDecodeException if the buffer ends before a fixed envelope field is fully read,
     *     the cause of transmission is undefined, or the variable structure qualifier reports a
     *     count outside {@code 0..127}.
     * @throws com.digitalpetri.iec104.UnsupportedAsduTypeException if the type identification is
     *     undefined or no codec is registered for it.
     */
    public static Asdu decode(
        ProtocolProfile profile, InformationObjectCodecRegistry registry, ByteBuf buffer) {

      int headerLength = 3 + (profile.cotLength() == 2 ? 1 : 0) + profile.commonAddressLength();
      if (buffer.readableBytes() < headerLength) {
        throw new AsduDecodeException(
            "truncated ASDU header: need "
                + headerLength
                + " octets but only "
                + buffer.readableBytes()
                + " readable");
      }

      AsduType type = AsduType.fromId(buffer.readUnsignedByte());

      int vsq = buffer.readUnsignedByte();
      boolean sequence = (vsq & 0x80) != 0;
      int count = vsq & 0x7F;

      int cot = buffer.readUnsignedByte();
      boolean test = (cot & 0x80) != 0;
      boolean negative = (cot & 0x40) != 0;
      Cause cause = Cause.fromValue(cot & 0x3F);

      OriginatorAddress originatorAddress;
      if (profile.cotLength() == 2) {
        originatorAddress = OriginatorAddress.of(buffer.readUnsignedByte());
      } else {
        originatorAddress = OriginatorAddress.none();
      }

      CommonAddress commonAddress =
          CommonAddress.Serde.decode(profile.commonAddressLength(), buffer);

      InformationObjectCodec<InformationObject> codec = registry.codecFor(type);

      List<InformationObject> objects = new ArrayList<>(count);
      if (sequence) {
        if (count > 0) {
          InformationObjectAddress base =
              InformationObjectAddress.Serde.decode(profile.ioaLength(), buffer);
          long baseValue = base.value().longValue();
          for (int i = 0; i < count; i++) {
            InformationObjectAddress address =
                (i == 0) ? base : InformationObjectAddress.of(baseValue + i);
            objects.add(codec.decode(address, buffer));
          }
        }
      } else {
        for (int i = 0; i < count; i++) {
          InformationObjectAddress address =
              InformationObjectAddress.Serde.decode(profile.ioaLength(), buffer);
          objects.add(codec.decode(address, buffer));
        }
      }

      return new Asdu(
          type, sequence, cause, negative, test, originatorAddress, commonAddress, objects);
    }
  }
}
