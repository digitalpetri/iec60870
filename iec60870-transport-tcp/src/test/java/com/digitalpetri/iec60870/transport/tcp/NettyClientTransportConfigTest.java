package com.digitalpetri.iec60870.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code connectTimeout} (IEC 104 {@code t0}) plumbing on {@link
 * NettyClientTransportConfig}.
 */
class NettyClientTransportConfigTest {

  @Test
  void defaultConnectTimeoutIsThirtySeconds() {
    NettyClientTransportConfig config =
        NettyClientTransportConfig.builder("127.0.0.1", 2404).build();

    assertEquals(Duration.ofSeconds(30), config.connectTimeout());
  }

  @Test
  void connectTimeoutRoundTrips() {
    NettyClientTransportConfig config =
        NettyClientTransportConfig.builder("127.0.0.1", 2404)
            .connectTimeout(Duration.ofMillis(7531))
            .build();

    assertEquals(Duration.ofMillis(7531), config.connectTimeout());
  }

  // Deliberate null passed to a @NotNull param to verify the builder rejects it.
  @SuppressWarnings("DataFlowIssue")
  @Test
  void rejectsNullConnectTimeout() {
    NettyClientTransportConfig.Builder builder =
        NettyClientTransportConfig.builder("127.0.0.1", 2404);

    assertThrows(NullPointerException.class, () -> builder.connectTimeout(null));
  }

  @Test
  void rejectsNonPositiveConnectTimeout() {
    NettyClientTransportConfig.Builder zero =
        NettyClientTransportConfig.builder("127.0.0.1", 2404).connectTimeout(Duration.ZERO);
    assertThrows(IllegalArgumentException.class, zero::build);

    NettyClientTransportConfig.Builder negative =
        NettyClientTransportConfig.builder("127.0.0.1", 2404)
            .connectTimeout(Duration.ofSeconds(-1));
    assertThrows(IllegalArgumentException.class, negative::build);
  }
}
