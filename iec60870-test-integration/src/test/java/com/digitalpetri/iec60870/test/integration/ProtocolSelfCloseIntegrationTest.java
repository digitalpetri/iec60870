package com.digitalpetri.iec60870.test.integration;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.SequenceNumberException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.ConnectionState;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.ReconnectPolicy;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real-Netty integration test for the protocol self-close path of {@link TcpIec104Client}: a
 * protocol error detected by the client's {@code ApciSession} closes the connection, and the client
 * then recovers according to its {@link ReconnectPolicy}, like after any other unplanned loss.
 *
 * <p>The protocol error is induced deterministically: after data transfer has started, the test
 * injects a raw, well-formed S-format frame carrying an impossible acknowledgement N(R) directly
 * onto the wire from the captured server-side child channel. The client has sent no I-frames, so a
 * non-zero N(R) is an invalid acknowledgement and {@code ApciSession} self-closes with a {@link
 * SequenceNumberException}.
 *
 * <p>With the default policy the client reconnects on its own. With {@link
 * ReconnectPolicy#disabled()} it stays disconnected, opens no TCP connection by itself, and a later
 * {@code connect()} succeeds on a new connection, also with the transport's private event loop
 * group.
 */
class ProtocolSelfCloseIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress SINGLE_POINT = PointAddress.of(1, 100);

  /** An acknowledgement N(R) the client cannot possibly expect (it has 0 outstanding I-frames). */
  private static final int IMPOSSIBLE_NR = 5000;

  /** A window over which to confirm that no connection is opened without connect(). */
  private static final Duration NO_RECONNECT_SETTLE = Duration.ofSeconds(3);

  private final ChannelGroup acceptedChildChannels =
      new DefaultChannelGroup("accepted-children", GlobalEventExecutor.INSTANCE);

  private final AtomicInteger acceptedConnections = new AtomicInteger();

  private @Nullable Iec60870Server server;
  private @Nullable Iec60870Client client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  void protocolSelfCloseReconnectsPerPolicy() throws Exception {
    EventCollector events =
        startServerAndClient(
            ReconnectPolicy.exponentialBackoff(Duration.ofMillis(100), Duration.ofSeconds(1)));
    Iec60870Client client = requireNonNull(this.client);

    client.connect();
    injectImpossibleAck();

    awaitWithin(
        "ConnectionClosed carrying the protocol SequenceNumberException cause",
        () ->
            events.hasMatch(
                ClientEvent.ConnectionClosed.class,
                c -> c.cause() instanceof SequenceNumberException),
        Await.DEFAULT_TIMEOUT);
    awaitWithin(
        "ReconnectScheduled carrying the protocol cause",
        () ->
            events.hasMatch(
                ClientEvent.ReconnectScheduled.class,
                r -> r.cause() instanceof SequenceNumberException),
        Await.DEFAULT_TIMEOUT);
    awaitWithin(
        "a second ConnectionOpened and DataTransferStarted without connect()",
        () ->
            count(events, ClientEvent.ConnectionOpened.class) == 2
                && count(events, ClientEvent.DataTransferStarted.class) == 2,
        Await.DEFAULT_TIMEOUT);
    awaitWithin("client reconnected", client::isConnected, Await.DEFAULT_TIMEOUT);
    assertEquals(2, acceptedConnections.get(), "the reconnect used a new TCP connection");
    assertTrue(client.interrogate(STATION).terminated());
  }

  @Test
  void withReconnectDisabledConnectAfterASelfCloseOpensANewConnection() throws Exception {
    EventCollector events = startServerAndClient(ReconnectPolicy.disabled());
    Iec60870Client client = requireNonNull(this.client);

    client.connect();
    injectImpossibleAck();

    awaitWithin(
        "ConnectionClosed carrying the protocol SequenceNumberException cause",
        () ->
            events.hasMatch(
                ClientEvent.ConnectionClosed.class,
                c -> c.cause() instanceof SequenceNumberException),
        Await.DEFAULT_TIMEOUT);
    assertEquals(ConnectionState.DISCONNECTED, client.connectionState());
    assertFalse(client.isConnected());

    // Long enough for the old private event loop group to have finished shutting down, and for a
    // transport that redials on its own to have done so.
    Thread.sleep(NO_RECONNECT_SETTLE.toMillis());
    assertEquals(1, acceptedConnections.get(), "no TCP connection appears without connect()");
    assertEquals(1, count(events, ClientEvent.ConnectionOpened.class));
    assertFalse(events.hasAny(ClientEvent.ReconnectScheduled.class));

    client.connect();

    assertTrue(client.isConnected());
    assertEquals(2, acceptedConnections.get(), "connect() dialed a new TCP connection");
    awaitWithin(
        "DataTransferStarted on the new connection",
        () -> count(events, ClientEvent.DataTransferStarted.class) == 2,
        Await.DEFAULT_TIMEOUT);
    assertTrue(client.interrogate(STATION).terminated());
  }

  /** Writes an S-frame with an impossible N(R) from the server side of the active connection. */
  private void injectImpossibleAck() {
    Channel child =
        acceptedChildChannels.stream()
            .filter(Channel::isActive)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no active captured server-side child channel"));
    child.writeAndFlush(sFrameWithBadAck(child, IMPOSSIBLE_NR)).awaitUninterruptibly();
  }

  /**
   * Builds a complete, well-formed S-format APDU frame ({@code 0x68 0x04 0x01 0x00 nrLo nrHi})
   * carrying the given receive sequence number, allocated from the channel's allocator.
   *
   * <p>The length octet is {@code 4} (the four control octets; START and length octets are
   * excluded), the first control octet {@code 0x01} selects S-format, and the N(R) is encoded
   * little-endian across octets 3 and 4 exactly as {@code ControlField.Serde} does on the wire.
   */
  private static ByteBuf sFrameWithBadAck(Channel channel, int nr) {
    ByteBuf frame = channel.alloc().buffer(6);
    frame.writeByte(0x68); // START
    frame.writeByte(0x04); // length = 4 control octets
    frame.writeByte(0x01); // S-format selector
    frame.writeByte(0x00);
    frame.writeByte((nr << 1) & 0xFE); // N(R) low octet
    frame.writeByte((nr >> 7) & 0xFF); // N(R) high octet
    return frame;
  }

  private EventCollector startServerAndClient(ReconnectPolicy policy) throws IOException {
    int port = reserveEphemeralPort();
    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    SINGLE_POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED))
            .build();

    server =
        TcpIec104Server.builder()
            .bindAddress("127.0.0.1")
            .port(port)
            .addStation(station)
            .serverBootstrapCustomizer(
                bootstrap ->
                    // Capture accepted children via handler() on the PARENT channel; childHandler
                    // (the IEC pipeline) is left untouched. No SO_LINGER: the close here is a clean
                    // protocol self-close from the client, not a server-side RST.
                    bootstrap.handler(
                        new ChannelInboundHandlerAdapter() {
                          @Override
                          public void channelRead(ChannelHandlerContext ctx, Object msg)
                              throws Exception {
                            if (msg instanceof Channel child) {
                              acceptedChildChannels.add(child);
                              acceptedConnections.incrementAndGet();
                            }
                            super.channelRead(ctx, msg);
                          }
                        }))
            .build();
    server.start();

    client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .startDataTransferOnConnect(true)
            .reconnectPolicy(policy)
            .build();

    EventCollector events = new EventCollector();
    client.events().subscribe(events);
    return events;
  }

  private static int count(EventCollector events, Class<? extends ClientEvent> type) {
    return (int) events.events().stream().filter(type::isInstance).count();
  }

  private static void awaitWithin(String description, BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        //noinspection BusyWait
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while polling for: " + description, e);
      }
    }
    if (!condition.getAsBoolean()) {
      throw new AssertionError(
          "timed out after " + timeout.toMillis() + " ms waiting for: " + description);
    }
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
