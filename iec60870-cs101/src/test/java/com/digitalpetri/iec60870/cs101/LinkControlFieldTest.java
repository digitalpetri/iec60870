package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Golden-octet tests for {@link LinkControlField}, the one-octet FT1.2 link control field.
 *
 * <p>The expected octet values are derived independently from the frozen FT1.2 codec specification
 * (not copied from the production encoder) so that a bug in {@link LinkControlField#toOctet()} or
 * {@link LinkControlField#fromOctet(int)} is caught. The encode formula under test is {@code octet
 * = (dir?0x80:0) | (prm?0x40:0) | (fcbOrAcd?0x20:0) | (fcvOrDfc?0x10:0) | (functionCode & 0x0F)}.
 */
class LinkControlFieldTest {

  // --- representative golden octets (hand-computed from the bit layout) --------------------------

  @Test
  void primaryUserDataSendConfirmOctet() {
    // primary(dir=false, fcb=true, fcv=true, fc=3):
    //   PRM(0x40) | FCB(0x20) | FCV(0x10) | fc=3(0x03) = 0x73.
    assertEquals(0x73, LinkControlField.primary(false, true, true, 3).toOctet());
  }

  @Test
  void secondaryAccessDemandOctet() {
    // secondary(dir=false, acd=true, dfc=false, fc=0):
    //   PRM=0, ACD(0x20), DFC=0, fc=0 = 0x20.
    assertEquals(0x20, LinkControlField.secondary(false, true, false, 0).toOctet());
  }

  @Test
  void primaryRequestStatusOfLinkWithDirectionOctet() {
    // primary(dir=true, fcb=false, fcv=false, fc=9):
    //   DIR(0x80) | PRM(0x40) | fc=9(0x09) = 0xC9.
    // (The design-contract example "0x89 (0x80|0x09)" dropped the PRM bit; every primary frame sets
    // PRM=0x40 per the frozen encode formula, so the spec-derived value is 0xC9.)
    assertEquals(0xC9, LinkControlField.primary(true, false, false, 9).toOctet());
  }

  // --- per-bit mask verification (one bit set at a time) -----------------------------------------

  @Test
  void eachBitMaskMapsToTheExpectedOctet() {
    // Canonical ctor order is (prm, fcbOrAcd, fcvOrDfc, dir, functionCode); isolate one bit each.
    assertEquals(0x80, new LinkControlField(false, false, false, true, 0).toOctet(), "DIR == 0x80");
    assertEquals(0x40, new LinkControlField(true, false, false, false, 0).toOctet(), "PRM == 0x40");
    assertEquals(
        0x20, new LinkControlField(false, true, false, false, 0).toOctet(), "FCB/ACD == 0x20");
    assertEquals(
        0x10, new LinkControlField(false, false, true, false, 0).toOctet(), "FCV/DFC == 0x10");
    assertEquals(
        0x0F, new LinkControlField(false, false, false, false, 15).toOctet(), "FC == 0x0F");
  }

  // --- decode of representative octets -----------------------------------------------------------

  @Test
  void fromOctetDecodesPrimaryBits() {
    LinkControlField field = LinkControlField.fromOctet(0x73);
    assertTrue(field.prm());
    assertFalse(field.dir());
    assertTrue(field.fcb());
    assertTrue(field.fcv());
    assertEquals(3, field.functionCode());
  }

  @Test
  void fromOctetDecodesSecondaryBits() {
    LinkControlField field = LinkControlField.fromOctet(0x20);
    assertFalse(field.prm());
    assertTrue(field.acd());
    assertFalse(field.dfc());
    assertEquals(0, field.functionCode());
  }

  @Test
  void fromOctetIgnoresBitsAboveTheLowEight() {
    // Only the low eight bits are inspected; 0x1FF decodes the same as 0xFF.
    assertEquals(LinkControlField.fromOctet(0xFF), LinkControlField.fromOctet(0x1FF));
  }

  // --- round trips -------------------------------------------------------------------------------

  @Test
  void primaryAndSecondaryFieldsRoundTrip() {
    LinkControlField primary = LinkControlField.primary(true, true, false, 3);
    assertEquals(primary, LinkControlField.fromOctet(primary.toOctet()));

    LinkControlField secondary = LinkControlField.secondary(false, false, true, 11);
    assertEquals(secondary, LinkControlField.fromOctet(secondary.toOctet()));
  }

  @Test
  void everyOctetRoundTripsThroughFromOctetAndBack() {
    // fromOctet(o).toOctet() must reproduce every octet value exactly.
    for (int octet = 0; octet <= 0xFF; octet++) {
      assertEquals(octet, LinkControlField.fromOctet(octet).toOctet(), "octet 0x" + octet);
    }
  }

  // --- function-code bounds ----------------------------------------------------------------------

  @Test
  void functionCodeFifteenIsAccepted() {
    assertEquals(15, LinkControlField.primary(false, false, false, 15).functionCode());
  }

  @Test
  void functionCodeSixteenIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> LinkControlField.primary(false, false, false, 16));
    assertThrows(
        IllegalArgumentException.class, () -> LinkControlField.secondary(false, false, false, 16));
    assertThrows(
        IllegalArgumentException.class, () -> new LinkControlField(true, false, false, false, 16));
  }

  @Test
  void negativeFunctionCodeIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> LinkControlField.primary(false, false, false, -1));
  }
}
