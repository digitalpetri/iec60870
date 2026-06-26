package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LinkSettings}: the builder defaults exposed by {@link
 * LinkSettings#balanced()} and {@link LinkSettings#unbalanced()}, and the validation performed by
 * the compact constructor.
 */
class LinkSettingsTest {

  private static final Duration CONFIRM = Duration.ofMillis(200);
  private static final Duration REPEAT = Duration.ofMillis(1000);
  private static final Duration LINK_STATE = Duration.ofMillis(5000);

  @Nested
  class BuilderDefaults {

    @Test
    void balancedDefaults() {
      LinkSettings settings = LinkSettings.balanced().build();

      assertEquals(LinkMode.BALANCED, settings.mode());
      assertEquals(1, settings.linkAddress());
      assertEquals(1, settings.linkAddressLength());
      assertEquals(255, settings.broadcastAddress());
      assertTrue(settings.useSingleCharAck());
      assertEquals(Duration.ofMillis(200), settings.confirmTimeout());
      assertEquals(Duration.ofMillis(1000), settings.repeatTimeout());
      assertEquals(3, settings.maxRetries());
      assertEquals(Duration.ofMillis(5000), settings.linkStateTimeout());
    }

    @Test
    void unbalancedDefaults() {
      LinkSettings settings = LinkSettings.unbalanced().build();

      assertEquals(LinkMode.UNBALANCED, settings.mode());
      assertEquals(1, settings.linkAddress());
      assertEquals(1, settings.linkAddressLength());
      assertEquals(255, settings.broadcastAddress());
      assertTrue(settings.useSingleCharAck());
      assertEquals(Duration.ofMillis(200), settings.confirmTimeout());
      assertEquals(Duration.ofMillis(1000), settings.repeatTimeout());
      assertEquals(3, settings.maxRetries());
      assertEquals(Duration.ofMillis(5000), settings.linkStateTimeout());
    }

    @Test
    void builderOverridesAreApplied() {
      LinkSettings settings =
          LinkSettings.unbalanced()
              .linkAddressLength(2)
              .linkAddress(5000)
              .broadcastAddress(65535)
              .useSingleCharAck(false)
              .confirmTimeout(Duration.ofMillis(300))
              .repeatTimeout(Duration.ofMillis(1500))
              .maxRetries(5)
              .linkStateTimeout(Duration.ofMillis(7000))
              .build();

      assertEquals(LinkMode.UNBALANCED, settings.mode());
      assertEquals(2, settings.linkAddressLength());
      assertEquals(5000, settings.linkAddress());
      assertEquals(65535, settings.broadcastAddress());
      assertFalse(settings.useSingleCharAck());
      assertEquals(Duration.ofMillis(300), settings.confirmTimeout());
      assertEquals(Duration.ofMillis(1500), settings.repeatTimeout());
      assertEquals(5, settings.maxRetries());
      assertEquals(Duration.ofMillis(7000), settings.linkStateTimeout());
    }
  }

  @Nested
  class ConstructorRejects {

    @Test
    void linkAddressLengthAboveTwo() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(LinkMode.BALANCED, 1, 3, 255, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void linkAddressLengthNegative() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.BALANCED, 0, -1, 255, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void unbalancedWithZeroAddressLength() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.UNBALANCED, 0, 0, 255, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void negativeLinkAddress() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.BALANCED, -1, 1, 255, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void nonZeroLinkAddressWithZeroLength() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(LinkMode.BALANCED, 5, 0, 255, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void linkAddress256WithLengthOne() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.BALANCED, 256, 1, 255, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void linkAddressAbove65535WithLengthTwo() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.BALANCED, 65536, 2, 255, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void broadcastAddressMismatchLengthOne() {
      // Length 1 requires broadcast 255, not 65535.
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.UNBALANCED, 1, 1, 65535, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void broadcastAddressMismatchLengthTwo() {
      // Length 2 requires broadcast 65535, not 255.
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.UNBALANCED, 1, 2, 255, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void broadcastAddressOutOfRange() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.BALANCED, 1, 1, 70000, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void negativeMaxRetries() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.BALANCED, 1, 1, 255, true, CONFIRM, REPEAT, -1, LINK_STATE));
    }

    @Test
    void zeroConfirmTimeout() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.BALANCED, 1, 1, 255, true, Duration.ZERO, REPEAT, 3, LINK_STATE));
    }

    @Test
    void negativeRepeatTimeout() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.BALANCED,
                  1,
                  1,
                  255,
                  true,
                  CONFIRM,
                  Duration.ofMillis(-1),
                  3,
                  LINK_STATE));
    }

    @Test
    void zeroLinkStateTimeout() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LinkSettings(
                  LinkMode.BALANCED, 1, 1, 255, true, CONFIRM, REPEAT, 3, Duration.ZERO));
    }

    @Test
    void nullModeRejected() {
      assertThrows(
          NullPointerException.class,
          () -> new LinkSettings(null, 1, 1, 255, true, CONFIRM, REPEAT, 3, LINK_STATE));
    }

    @Test
    void nullDurationRejected() {
      assertThrows(
          NullPointerException.class,
          () -> new LinkSettings(LinkMode.BALANCED, 1, 1, 255, true, null, REPEAT, 3, LINK_STATE));
    }
  }

  @Nested
  class ConstructorAccepts {

    @Test
    void balancedAbsentAddress() {
      LinkSettings settings =
          new LinkSettings(LinkMode.BALANCED, 0, 0, 255, true, CONFIRM, REPEAT, 0, LINK_STATE);

      assertEquals(0, settings.linkAddressLength());
      assertEquals(0, settings.linkAddress());
      assertEquals(0, settings.maxRetries());
    }

    @Test
    void balancedMaxOneOctetAddress() {
      LinkSettings settings =
          new LinkSettings(LinkMode.BALANCED, 255, 1, 255, true, CONFIRM, REPEAT, 3, LINK_STATE);

      assertEquals(255, settings.linkAddress());
      assertEquals(1, settings.linkAddressLength());
    }

    @Test
    void balancedMaxTwoOctetAddress() {
      LinkSettings settings =
          new LinkSettings(LinkMode.BALANCED, 65535, 2, 255, true, CONFIRM, REPEAT, 3, LINK_STATE);

      assertEquals(65535, settings.linkAddress());
      assertEquals(2, settings.linkAddressLength());
    }

    @Test
    void unbalancedOneOctet() {
      LinkSettings settings =
          new LinkSettings(LinkMode.UNBALANCED, 200, 1, 255, true, CONFIRM, REPEAT, 3, LINK_STATE);

      assertEquals(LinkMode.UNBALANCED, settings.mode());
      assertEquals(200, settings.linkAddress());
      assertEquals(255, settings.broadcastAddress());
    }

    @Test
    void unbalancedTwoOctet() {
      LinkSettings settings =
          new LinkSettings(
              LinkMode.UNBALANCED, 5000, 2, 65535, true, CONFIRM, REPEAT, 3, LINK_STATE);

      assertEquals(LinkMode.UNBALANCED, settings.mode());
      assertEquals(5000, settings.linkAddress());
      assertEquals(65535, settings.broadcastAddress());
    }
  }
}
