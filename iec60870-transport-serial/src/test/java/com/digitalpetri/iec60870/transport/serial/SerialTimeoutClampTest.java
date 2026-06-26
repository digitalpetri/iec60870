package com.digitalpetri.iec60870.transport.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Regression test for finding #14: a timeout {@link Duration} must not be lossily narrowed to
 * {@code int} milliseconds before reaching the serial driver. A sub-millisecond timeout would
 * truncate to {@code 0} (which {@code jSerialComm} treats as block-indefinitely, stalling the
 * reader forever), and a multi-day timeout would overflow {@code int} to a negative value.
 *
 * <p>The conversion is clamped defensively at the driver boundary by {@link
 * Ft12SerialChannel#toTimeoutMillis(Duration)} and rejected up front by {@link SerialPortConfig},
 * so both layers are covered here without opening a real serial port.
 */
class SerialTimeoutClampTest {

  @Test
  void subMillisecondTimeoutClampsToOneMilli() {
    // 0.5 ms truncates to 0 ms under a plain (int) toMillis() narrowing; it must clamp up to 1.
    assertEquals(1, Ft12SerialChannel.toTimeoutMillis(Duration.ofNanos(500_000)));
    assertEquals(1, Ft12SerialChannel.toTimeoutMillis(Duration.ofNanos(1)));
  }

  @Test
  void exactMillisecondsPassThroughUnchanged() {
    assertEquals(1, Ft12SerialChannel.toTimeoutMillis(Duration.ofMillis(1)));
    assertEquals(100, Ft12SerialChannel.toTimeoutMillis(Duration.ofMillis(100)));
    assertEquals(
        Integer.MAX_VALUE, Ft12SerialChannel.toTimeoutMillis(Duration.ofMillis(Integer.MAX_VALUE)));
  }

  @Test
  void veryLargeTimeoutClampsToMaxIntAndStaysPositive() {
    // ofDays(30) is ~2.59e9 ms; a plain (int) narrowing overflows to a negative value.
    int days = Ft12SerialChannel.toTimeoutMillis(Duration.ofDays(30));
    assertEquals(Integer.MAX_VALUE, days);
    assertTrue(days > 0, "clamped timeout must stay positive, not overflow to negative");

    int justOver = Ft12SerialChannel.toTimeoutMillis(Duration.ofMillis(Integer.MAX_VALUE + 1L));
    assertEquals(Integer.MAX_VALUE, justOver);
    assertTrue(justOver > 0, "clamped timeout must stay positive, not overflow to negative");
  }

  @Test
  void configRejectsSubMillisecondReadTimeout() {
    SerialPortConfig.Builder builder =
        SerialPortConfig.builder("/dev/ttyUSB0").readTimeout(Duration.ofNanos(500_000));

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void configRejectsSubMillisecondWriteTimeout() {
    SerialPortConfig.Builder builder =
        SerialPortConfig.builder("/dev/ttyUSB0").writeTimeout(Duration.ofNanos(1));

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void configRejectsTimeoutBeyondIntMillisRange() {
    SerialPortConfig.Builder readBuilder =
        SerialPortConfig.builder("/dev/ttyUSB0").readTimeout(Duration.ofDays(30));
    assertThrows(IllegalArgumentException.class, readBuilder::build);

    SerialPortConfig.Builder writeBuilder =
        SerialPortConfig.builder("/dev/ttyUSB0")
            .writeTimeout(Duration.ofMillis(Integer.MAX_VALUE + 1L));
    assertThrows(IllegalArgumentException.class, writeBuilder::build);
  }

  @Test
  void configAcceptsTimeoutsAtTheBoundaries() {
    SerialPortConfig config =
        SerialPortConfig.builder("/dev/ttyUSB0")
            .readTimeout(Duration.ofMillis(1))
            .writeTimeout(Duration.ofMillis(Integer.MAX_VALUE))
            .build();

    assertEquals(Duration.ofMillis(1), config.readTimeout());
    assertEquals(Duration.ofMillis(Integer.MAX_VALUE), config.writeTimeout());
  }
}
