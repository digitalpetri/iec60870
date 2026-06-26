package com.digitalpetri.iec60870.cs101;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.cs101.LinkSettings.PollConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression tests guarding against silently truncating a link/slave address that does not fit the
 * configured {@link LinkSettings#linkAddressLength()}.
 *
 * <p>{@link LinkSettings} must reject an out-of-range poll slave address at configuration time
 * (fail fast) rather than letting {@link Ft12Framer} encode only its low octets on the wire, which
 * would mis-address every frame to a station that never answers. {@link Ft12Framer} keeps a
 * defensive backstop that throws instead of truncating.
 */
class LinkAddressRangeValidationTest {

  private static final Duration CONFIRM = Duration.ofMillis(200);
  private static final Duration REPEAT = Duration.ofMillis(1000);
  private static final Duration LINK_STATE = Duration.ofMillis(5000);
  private static final Duration POLL = Duration.ofMillis(1000);

  /** CS101-style profile: 1-octet COT, 1-octet common address, 2-octet IOA. */
  private static final ProtocolProfile PROFILE = new ProtocolProfile(1, 1, 2, 255);

  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;

  @Nested
  class SettingsRejectOutOfRangeSlaveAddress {

    @Test
    void slaveAddress256WithLengthOneRejected() {
      // Default unbalanced settings use a one-octet address (max 255); 256 cannot be represented.
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> LinkSettings.unbalanced().slaveAddresses(List.of(256)).build());
      assertTrue(
          ex.getMessage().contains("256"), "message should name the offending address: " + ex);
    }

    @Test
    void slaveAddressAbove65535WithLengthTwoRejected() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              LinkSettings.unbalanced()
                  .linkAddressLength(2)
                  .broadcastAddress(65535)
                  .slaveAddresses(List.of(65536))
                  .build());
    }

    @Test
    void slaveAddressEqualToBroadcastRejected() {
      // 255 is the all-secondaries broadcast address for a one-octet link; an individual poll
      // target must not use it.
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> LinkSettings.unbalanced().slaveAddresses(List.of(255)).build());
      assertTrue(
          ex.getMessage().contains("broadcast"),
          "message should explain the broadcast conflict: " + ex);
    }

    @Test
    void directConstructorRejectsOutOfRangeSlaveAddress() {
      // The cross-validation applies whether the PollConfig is built or passed straight in.
      PollConfig pollConfig = new PollConfig(List.of(256), POLL);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.UNBALANCED,
                  1,
                  1,
                  255,
                  true,
                  CONFIRM,
                  REPEAT,
                  3,
                  LINK_STATE,
                  pollConfig));
    }
  }

  @Nested
  class SettingsAcceptInRangeSlaveAddress {

    @Test
    void oneOctetSlaveAddressesWithinRangeAccepted() {
      LinkSettings settings = LinkSettings.unbalanced().slaveAddresses(List.of(1, 254)).build();

      PollConfig pollConfig = requireNonNull(settings.pollConfig());
      assertEquals(List.of(1, 254), pollConfig.slaveAddresses());
    }

    @Test
    void twoOctetSlaveAddressNearMaxAccepted() {
      LinkSettings settings =
          LinkSettings.unbalanced()
              .linkAddressLength(2)
              .broadcastAddress(65535)
              .slaveAddresses(List.of(65534))
              .build();

      PollConfig pollConfig = requireNonNull(settings.pollConfig());
      assertEquals(List.of(65534), pollConfig.slaveAddresses());
    }
  }

  @Nested
  class FramerBackstop {

    @Test
    void encodeRejectsAddress256AtLengthOne() {
      Ft12Frame frame =
          new Ft12Frame.FixedLength(LinkControlField.primary(false, false, false, 9), 256);
      assertThrows(
          IllegalArgumentException.class, () -> Ft12Framer.encode(frame, PROFILE, 1, ALLOC));
    }

    @Test
    void encodeRejectsAddress65536AtLengthTwo() {
      Ft12Frame frame =
          new Ft12Frame.FixedLength(LinkControlField.primary(false, false, false, 9), 65536);
      assertThrows(
          IllegalArgumentException.class, () -> Ft12Framer.encode(frame, PROFILE, 2, ALLOC));
    }

    @Test
    void encodeAcceptsAddressAtMaxBoundary() {
      Ft12Frame frame =
          new Ft12Frame.FixedLength(LinkControlField.primary(false, false, false, 9), 255);
      ByteBuf encoded = Ft12Framer.encode(frame, PROFILE, 1, ALLOC);
      try {
        assertTrue(encoded.isReadable(), "an in-range address should encode normally");
      } finally {
        encoded.release();
      }
    }
  }
}
