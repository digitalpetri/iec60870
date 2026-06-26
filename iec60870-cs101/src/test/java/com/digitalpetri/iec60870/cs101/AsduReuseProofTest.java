package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proof that the existing {@code iec60870-core} ASDU model carries CS101 traffic unchanged.
 *
 * <p>This test asserts <b>zero core changes</b>: the very same {@link Asdu} value round-trips
 * identically through (a) the core {@link Asdu.Serde} directly and (b) a CS101 {@link
 * Ft12Frame.Variable} carried by {@link Ft12Framer}. CS101 reuses a different {@link
 * ProtocolProfile} (a 1-octet cause of transmission, 1-octet common address, and 2-octet
 * information object address) than IEC 104, yet the core codec needs no modification to serve it.
 * {@code git diff iec60870-core} must therefore stay empty.
 */
class AsduReuseProofTest {

  /** CS101-style field widths: COT=1 (no originator), common address=1, IOA=2. */
  private static final ProtocolProfile PROFILE = new ProtocolProfile(1, 1, 2, 255);

  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;

  /** Builds a real single-point ASDU exercising the data unit identifier and one object. */
  private static Asdu sampleAsdu() {
    InformationObject object =
        new SinglePointInformation(
            InformationObjectAddress.of(100), true, new Qds(false, false, false, false, false));
    return new Asdu(
        AsduType.M_SP_NA_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(object));
  }

  @Test
  void asduRoundTripsThroughCoreSerdeUnderCs101Profile() {
    Asdu asdu = sampleAsdu();

    ByteBuf buffer = Unpooled.buffer();
    try {
      Asdu.Serde.encode(asdu, PROFILE, buffer);
      Asdu decoded = Asdu.Serde.decode(PROFILE, buffer);

      assertEquals(asdu, decoded);
      assertEquals(0, buffer.readableBytes(), "decode should consume the whole ASDU");
    } finally {
      buffer.release();
    }
  }

  @Test
  void asduRoundTripsThroughFt12VariableFrame() {
    Asdu asdu = sampleAsdu();
    Ft12Frame.Variable frame =
        new Ft12Frame.Variable(LinkControlField.primary(false, true, true, 3), 1, asdu);

    ByteBuf encoded = Ft12Framer.encode(frame, PROFILE, 1, ALLOC);
    try {
      Ft12Frame.Variable decoded =
          assertInstanceOf(Ft12Frame.Variable.class, Ft12Framer.decode(PROFILE, 1, encoded));

      // The unmodified core Asdu emerges intact from the FT1.2 wrapper.
      assertEquals(asdu, decoded.asdu());
      assertEquals(frame, decoded);
    } finally {
      encoded.release();
    }
  }

  @Test
  void coreSerdeAndFt12CarryIdenticalAsduBytes() {
    Asdu asdu = sampleAsdu();

    // ASDU octets produced by the core Serde directly.
    byte[] coreBytes;
    ByteBuf core = Unpooled.buffer();
    try {
      Asdu.Serde.encode(asdu, PROFILE, core);
      coreBytes = new byte[core.readableBytes()];
      core.getBytes(core.readerIndex(), coreBytes);
    } finally {
      core.release();
    }

    // The same octets must appear verbatim inside the FT1.2 variable frame, after the 4-octet
    // header (0x68, L, L, 0x68) and the 1-octet control field plus 1-octet link address, and before
    // the trailing checksum and end octets.
    Ft12Frame.Variable frame =
        new Ft12Frame.Variable(LinkControlField.primary(false, true, true, 3), 1, asdu);
    ByteBuf encoded = Ft12Framer.encode(frame, PROFILE, 1, ALLOC);
    try {
      int asduStart = 4 + 1 + 1; // header + control + 1-octet link address.
      byte[] embedded = new byte[coreBytes.length];
      encoded.getBytes(encoded.readerIndex() + asduStart, embedded);
      assertArrayEquals(coreBytes, embedded);
    } finally {
      encoded.release();
    }
  }
}
