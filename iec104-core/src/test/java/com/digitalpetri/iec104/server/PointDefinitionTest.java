package com.digitalpetri.iec104.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.PointValue;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class PointDefinitionTest {

  @Test
  void capabilitiesAccessorReturnsIndependentCopy() {
    PointDefinition<Boolean> def =
        PointDefinition.of(
            PointAddress.of(1, 100),
            PointType.SINGLE_POINT,
            PointValue.single(false),
            PointCapability.REPORTED);

    def.capabilities().clear();
    def.capabilities().add(PointCapability.COMMANDABLE);

    assertTrue(def.hasCapability(PointCapability.REPORTED));
    assertFalse(def.hasCapability(PointCapability.COMMANDABLE));
    assertEquals(EnumSet.of(PointCapability.REPORTED), def.capabilities());
  }

  @Test
  void constructorRejectsMismatchedInitialValueType() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PointDefinition.of(
                PointAddress.of(1, 100),
                PointType.SCALED,
                PointValue.single(false),
                PointCapability.REPORTED));
  }

  @Test
  void constructorAcceptsMatchedInitialValueType() {
    PointDefinition<Boolean> def =
        PointDefinition.of(
            PointAddress.of(1, 100),
            PointType.SINGLE_POINT,
            PointValue.single(false),
            PointCapability.REPORTED);

    assertEquals(PointType.SINGLE_POINT, def.type());
  }
}
