package com.digitalpetri.iec60870.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReconnectPolicyTest {

  @Test
  void defaultsDoubleFromOneSecondUpTo32Seconds() {
    ReconnectPolicy policy = ReconnectPolicy.defaults();

    assertTrue(policy.enabled());
    assertEquals(Duration.ofSeconds(1), policy.delay(1));
    assertEquals(Duration.ofSeconds(2), policy.delay(2));
    assertEquals(Duration.ofSeconds(16), policy.delay(5));
    assertEquals(Duration.ofSeconds(32), policy.delay(6));
    assertEquals(Duration.ofSeconds(32), policy.delay(7));
    assertEquals(Duration.ofSeconds(32), policy.delay(Integer.MAX_VALUE));
  }

  @Test
  void delayIsCappedAtAMaximumThatIsNotAPowerOfTwoMultiple() {
    ReconnectPolicy policy =
        ReconnectPolicy.exponentialBackoff(Duration.ofMillis(300), Duration.ofSeconds(1));

    assertEquals(Duration.ofMillis(300), policy.delay(1));
    assertEquals(Duration.ofMillis(600), policy.delay(2));
    assertEquals(Duration.ofSeconds(1), policy.delay(3));
  }

  @Test
  void disabledPolicyHasNoDelay() {
    ReconnectPolicy policy = ReconnectPolicy.disabled();

    assertFalse(policy.enabled());
    assertThrows(IllegalStateException.class, () -> policy.delay(1));
  }

  @Test
  void invalidArgumentsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ReconnectPolicy.exponentialBackoff(Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ReconnectPolicy.exponentialBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> ReconnectPolicy.defaults().delay(0));
  }

  @Test
  void equalPoliciesAreEqual() {
    assertEquals(
        ReconnectPolicy.defaults(),
        ReconnectPolicy.exponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(32)));
  }
}
