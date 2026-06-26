package com.digitalpetri.iec60870;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProtocolProfileTest {

  @Test
  void iec104DefaultUsesStandardWidths() {
    ProtocolProfile profile = ProtocolProfile.iec104Default();

    assertEquals(2, profile.cotLength());
    assertEquals(2, profile.commonAddressLength());
    assertEquals(3, profile.ioaLength());
    assertEquals(249, profile.maxAsduLength());
  }

  @Test
  void iec101DefaultUsesNarrow101Widths() {
    ProtocolProfile profile = ProtocolProfile.iec101Default();

    assertEquals(1, profile.cotLength());
    assertEquals(1, profile.commonAddressLength());
    assertEquals(2, profile.ioaLength());
    assertEquals(255, profile.maxAsduLength());
  }

  @Test
  void iec101DefaultEqualsExplicitWidths() {
    assertEquals(new ProtocolProfile(1, 1, 2, 255), ProtocolProfile.iec101Default());
  }

  @Test
  void iec101DefaultDiffersFromIec104Default() {
    assertNotEquals(ProtocolProfile.iec104Default(), ProtocolProfile.iec101Default());
  }

  @Test
  void maxAsduLengthAcceptsSingleOctetCeiling() {
    assertDoesNotThrow(() -> new ProtocolProfile(2, 2, 3, 255));
  }

  @Test
  void maxAsduLengthRejectsAboveSingleOctetCeiling() {
    assertThrows(IllegalArgumentException.class, () -> new ProtocolProfile(2, 2, 3, 256));
  }
}
