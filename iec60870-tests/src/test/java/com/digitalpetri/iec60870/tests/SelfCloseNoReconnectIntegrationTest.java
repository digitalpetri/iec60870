package com.digitalpetri.iec60870.tests;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.SequenceNumberException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Iec60870Client;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real-Netty integration test for the protocol self-close path of {@link TcpIec104Client}: a
 * protocol error detected by the client's {@code ApciSession} must close the connection AND
 * suppress reconnection, in direct contrast to an unsolicited transport drop (which {@link
 * ReconnectIntegrationTest} proves DOES reconnect).
 *
 * <p>This is the inverse of the reconnect regression guard. A protocol self-close routes through
 * {@code Session.Events.onClosed}, which DOES call {@code transport.disconnect()}; that fires
 * {@code Event.Disconnect} on the persistent {@code ChannelFsm}, taking it permanently out of the
 * reconnect loop. So after the self-close NO second {@code ConnectionOpened} ever follows. An
 * unsolicited drop, by contrast, routes through {@code onConnectionLost}, which does not
 * disconnect, and the FSM reconnects.
 *
 * <p>The protocol error is induced deterministically (event-driven, NOT clock-driven): after data
 * transfer has started, the test injects a raw, well-formed S-format frame carrying an impossible
 * acknowledgement N(R) directly onto the wire from the captured server-side child channel. Because
 * the transport's outbound path is a raw {@code ByteBuf} write with no encoder (see {@code
 * Iec104Pipeline}), writing the bytes to the child channel puts them straight on the socket. The
 * client has sent no I-frames (it only performed STARTDT), so it has zero outstanding frames; a
 * non-zero N(R) is therefore an invalid acknowledgement and {@code ApciSession} self-closes with a
 * {@link SequenceNumberException}.
 *
 * <p>NON-FLAKY discipline: the close is triggered by a frame the test injects, not by any timer, so
 * the failure is deterministic. The only timing element is the inverse assertion — that NO
 * reconnect occurs — which is bounded by a GENEROUS settle window well beyond the ~1s first
 * reconnect backoff that the positive reconnect test relies on. Assertions are on event
 * presence/counts and connection state, never on durations.
 */
class SelfCloseNoReconnectIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress SINGLE_POINT = PointAddress.of(1, 100);

  /** An acknowledgement N(R) the client cannot possibly expect (it has 0 outstanding I-frames). */
  private static final int IMPOSSIBLE_NR = 5000;

  /**
   * A generous window over which to confirm NO reconnect happens. It is well beyond the ~1s first
   * reconnect backoff that {@link ReconnectIntegrationTest} relies on, so a reconnect (if the
   * suppression regressed) would have occurred within it.
   */
  private static final Duration NO_RECONNECT_SETTLE = Duration.ofSeconds(5);

  private final ChannelGroup acceptedChildChannels =
      new DefaultChannelGroup("accepted-children", GlobalEventExecutor.INSTANCE);

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
  void protocolSelfCloseClosesWithCauseAndDoesNotReconnect() throws Exception {
    int port = reserveEphemeralPort();
    EventCollector events = startServerAndClient(port);
    Iec60870Client client = requireNonNull(this.client);

    // 1. Establish the session and confirm data transfer started.
    client.connect();
    assertTrue(client.isConnected(), "client should be connected after connect()");
    awaitWithin(
        "DataTransferStarted",
        () -> count(events, ClientEvent.DataTransferStarted.class) >= 1,
        Await.DEFAULT_TIMEOUT);
    int opensAfterConnect = count(events, ClientEvent.ConnectionOpened.class);

    Channel child =
        acceptedChildChannels.stream()
            .filter(Channel::isActive)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no active captured server-side child channel"));

    // 2. Inject a well-formed S-frame with an impossible acknowledgement N(R) straight onto the
    // wire.
    // The client has 0 outstanding I-frames, so this is an invalid acknowledgement and the session
    // self-closes with a SequenceNumberException. This is deterministic: it fires on receipt of the
    // frame, not on any clock.
    child.writeAndFlush(sFrameWithBadAck(child, IMPOSSIBLE_NR)).awaitUninterruptibly();

    // 3a. The client must observe ConnectionClosed whose cause is the protocol
    // SequenceNumberException.
    awaitWithin(
        "ConnectionClosed carrying the protocol SequenceNumberException cause",
        () ->
            events.hasMatch(
                ClientEvent.ConnectionClosed.class,
                c -> c.causeOptional().orElse(null) instanceof SequenceNumberException),
        Await.DEFAULT_TIMEOUT);

    // 3b. THE load-bearing inverse assertion. The protocol self-close called
    // transport.disconnect(), so the persistent FSM is OUT of the reconnect loop: over a generous
    // settle window NO second ConnectionOpened appears and the client stays disconnected. Contrast
    // ReconnectIntegrationTest, which asserts the OPPOSITE for an unsolicited drop.
    awaitWithin(
        "transport observed the self-close (FSM left Connected)",
        () -> !client.isConnected(),
        Await.DEFAULT_TIMEOUT);
    assertNoSecondOpenWithin(events, opensAfterConnect, NO_RECONNECT_SETTLE);
    assertFalse(
        client.isConnected(), "after a protocol self-close the FSM must stay down (no reconnect)");
    assertExactlyOneClose(events);
  }

  /**
   * Asserts that no further {@link ClientEvent.ConnectionOpened} is published beyond {@code
   * opensBefore} for the full settle window — proving reconnection was suppressed.
   */
  private static void assertNoSecondOpenWithin(
      EventCollector events, int opensBefore, Duration settle) {
    long deadline = System.nanoTime() + settle.toNanos();
    while (System.nanoTime() < deadline) {
      int opens = count(events, ClientEvent.ConnectionOpened.class);
      if (opens > opensBefore) {
        throw new AssertionError(
            "a second ConnectionOpened appeared after a protocol self-close (reconnect was not "
                + "suppressed): opens="
                + opens
                + ", expected to stay at "
                + opensBefore);
      }
      try {
        //noinspection BusyWait
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while settling for no-reconnect", e);
      }
    }
  }

  /** Asserts exactly one ConnectionClosed was published for the single self-close. */
  private static void assertExactlyOneClose(EventCollector events) {
    int closes = count(events, ClientEvent.ConnectionClosed.class);
    if (closes != 1) {
      throw new AssertionError("expected exactly one ConnectionClosed, saw " + closes);
    }
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

  private EventCollector startServerAndClient(int port) {
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
            .build();

    EventCollector events = new EventCollector();
    client.events().subscribe(events);
    return events;
  }

  private static int count(EventCollector events, Class<? extends ClientEvent> type) {
    return (int) events.events().stream().filter(type::isInstance).count();
  }

  private static void awaitWithin(
      String description, java.util.function.BooleanSupplier condition, Duration timeout) {
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
