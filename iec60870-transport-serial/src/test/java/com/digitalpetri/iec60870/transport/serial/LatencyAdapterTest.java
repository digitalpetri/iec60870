package com.digitalpetri.iec60870.transport.serial;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Ft12SerialChannel#isHighLatencyAdapter(String)}, the pure heuristic that
 * decides whether an opened port's description names a known high-latency USB-serial adapter (an
 * FTDI / FT232 family device) so the channel can warn about the driver latency timer.
 *
 * <p>The helper is package-private and side-effect-free, so it can be exercised here without
 * opening a real serial port.
 */
class LatencyAdapterTest {

  @Test
  void detectsFtdiByName() {
    assertTrue(Ft12SerialChannel.isHighLatencyAdapter("FTDI USB Serial Device"));
  }

  @Test
  void detectsFt232ByName() {
    assertTrue(Ft12SerialChannel.isHighLatencyAdapter("FT232R USB UART"));
  }

  @Test
  void matchesCaseInsensitively() {
    assertTrue(Ft12SerialChannel.isHighLatencyAdapter("ftdi"));
    assertTrue(Ft12SerialChannel.isHighLatencyAdapter("ft232"));
    assertTrue(Ft12SerialChannel.isHighLatencyAdapter("Ftdi Friendly Name"));
  }

  @Test
  void matchesWhenTokenIsEmbedded() {
    assertTrue(Ft12SerialChannel.isHighLatencyAdapter("USB <-> Serial Cable (FTDI)"));
  }

  @Test
  void plainUsbSerialPortIsNotHighLatency() {
    assertFalse(Ft12SerialChannel.isHighLatencyAdapter("USB Serial Port"));
  }

  @Test
  void nullDescriptionIsNotHighLatency() {
    assertFalse(Ft12SerialChannel.isHighLatencyAdapter(null));
  }

  @Test
  void emptyDescriptionIsNotHighLatency() {
    assertFalse(Ft12SerialChannel.isHighLatencyAdapter(""));
  }

  @Test
  void blankDescriptionIsNotHighLatency() {
    assertFalse(Ft12SerialChannel.isHighLatencyAdapter("   "));
  }
}
