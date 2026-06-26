package com.digitalpetri.iec60870.tcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the IEC 104 {@code t0} parameter is wired through to the client's Netty bootstrap
 * as the {@link ChannelOption#CONNECT_TIMEOUT_MILLIS} channel option.
 *
 * <p>A loopback {@link TcpIec104Server} is bound on an ephemeral port and a {@link TcpIec104Client}
 * configured with a distinctive {@code t0} connects to it. The client's {@code bootstrapCustomizer}
 * runs after the transport sets the connect-timeout option (and before the socket connect), so it
 * observes the option the transport applied. The test asserts that observed value equals {@code
 * t0}, which fails if {@code t0} is left unwired (the regression this guards against).
 */
class ClientConnectTimeoutTest {

  @Test
  void t0IsAppliedAsConnectTimeoutMillis() throws Exception {
    int port = reserveEphemeralPort();
    Duration t0 = Duration.ofMillis(7531);

    ApciSettings apci =
        new ApciSettings(
            UShort.valueOf(12),
            UShort.valueOf(8),
            t0,
            Duration.ofSeconds(15),
            Duration.ofSeconds(10),
            Duration.ofSeconds(20));

    AtomicReference<@Nullable Object> observed = new AtomicReference<>();

    Station station =
        Station.builder(CommonAddress.of(1))
            .point(
                PointDefinition.of(
                    PointAddress.of(1, 100),
                    PointType.SINGLE_POINT,
                    PointValue.single(true),
                    PointCapability.REPORTED))
            .build();

    try (Iec60870Server server =
            TcpIec104Server.builder()
                .bindAddress("127.0.0.1")
                .port(port)
                .addStation(station)
                .build();
        Iec60870Client client =
            TcpIec104Client.builder()
                .host("127.0.0.1")
                .port(port)
                .apci(apci)
                .startDataTransferOnConnect(true)
                .bootstrapCustomizer(
                    bootstrap ->
                        observed.set(
                            bootstrap.config().options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS)))
                .build()) {
      server.start();
      client.connect();

      assertNotNull(
          observed.get(), "bootstrap customizer should have observed the connect timeout");
      assertEquals((int) t0.toMillis(), observed.get());
    }
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
