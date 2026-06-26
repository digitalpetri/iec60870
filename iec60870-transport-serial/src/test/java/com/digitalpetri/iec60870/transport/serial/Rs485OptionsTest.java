package com.digitalpetri.iec60870.transport.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Rs485Options} and how it threads into a {@link SerialPortConfig}.
 *
 * <p>These tests cover only the pure, in-JVM behavior — the builder defaults, the turnaround-delay
 * validation, and the fact that the options reach {@link SerialPortConfig#rs485()}. The
 * driver-level effect of the options (the {@code setRs485ModeParameters} call in {@link
 * Ft12SerialChannel}) cannot be exercised in CI because it requires opening a real serial port.
 */
class Rs485OptionsTest {

  @Test
  void builderDefaults() {
    Rs485Options options = Rs485Options.builder().build();

    assertTrue(options.rtsActiveHigh(), "RTS should default to active-high while transmitting");
    assertFalse(options.enableTermination(), "bus termination should default to off");
    assertFalse(options.rxDuringTx(), "receive-during-transmit should default to off");
    assertEquals(0, options.delayBeforeSendMicros());
    assertEquals(0, options.delayAfterSendMicros());
  }

  @Test
  void builderOverridesThreadThrough() {
    Rs485Options options =
        Rs485Options.builder()
            .rtsActiveHigh(false)
            .enableTermination(true)
            .rxDuringTx(true)
            .delayBeforeSendMicros(250)
            .delayAfterSendMicros(500)
            .build();

    assertFalse(options.rtsActiveHigh());
    assertTrue(options.enableTermination());
    assertTrue(options.rxDuringTx());
    assertEquals(250, options.delayBeforeSendMicros());
    assertEquals(500, options.delayAfterSendMicros());
  }

  @Test
  void zeroDelaysAreAccepted() {
    Rs485Options options =
        Rs485Options.builder().delayBeforeSendMicros(0).delayAfterSendMicros(0).build();

    assertEquals(0, options.delayBeforeSendMicros());
    assertEquals(0, options.delayAfterSendMicros());
  }

  @Test
  void rejectsNegativeDelayBeforeSendViaBuilder() {
    Rs485Options.Builder builder = Rs485Options.builder().delayBeforeSendMicros(-1);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void rejectsNegativeDelayAfterSendViaBuilder() {
    Rs485Options.Builder builder = Rs485Options.builder().delayAfterSendMicros(-1);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void rejectsNegativeDelayBeforeSendViaConstructor() {
    assertThrows(IllegalArgumentException.class, () -> new Rs485Options(true, false, false, -1, 0));
  }

  @Test
  void rejectsNegativeDelayAfterSendViaConstructor() {
    assertThrows(IllegalArgumentException.class, () -> new Rs485Options(true, false, false, 0, -1));
  }

  @Test
  void serialPortConfigDefaultsToNoRs485() {
    SerialPortConfig config = SerialPortConfig.builder("/dev/ttyUSB0").build();

    assertNull(config.rs485(), "RS-485 mode should be off (null) by default");
  }

  @Test
  void rs485ThreadsIntoSerialPortConfig() {
    Rs485Options options =
        Rs485Options.builder()
            .rtsActiveHigh(false)
            .enableTermination(true)
            .delayAfterSendMicros(750)
            .build();

    SerialPortConfig config = SerialPortConfig.builder("/dev/ttyUSB0").rs485(options).build();

    assertSame(options, config.rs485(), "config should carry the exact Rs485Options instance");
  }

  @Test
  void rs485CanBeClearedBackToNull() {
    SerialPortConfig config =
        SerialPortConfig.builder("/dev/ttyUSB0")
            .rs485(Rs485Options.builder().build())
            .rs485(null)
            .build();

    assertNull(config.rs485());
  }
}
