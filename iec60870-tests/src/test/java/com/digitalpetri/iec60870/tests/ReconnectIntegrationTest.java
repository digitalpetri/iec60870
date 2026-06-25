package com.digitalpetri.iec60870.tests;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Server;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real-Netty integration test that proves a {@link TcpIec104Client} automatically re-establishes
 * its session after an <em>unsolicited</em> mid-session transport drop, exercising the persistent
 * {@code ChannelFsm} reconnect path end-to-end over a loopback {@link TcpIec104Server}.
 *
 * <p>This guards the regression where {@code DefaultIec60870Client.SessionEvents.onConnectionLost}
 * must publish {@link ClientEvent.ConnectionClosed} without calling {@code transport.disconnect()}:
 * disconnecting would fire {@code Event.Disconnect} on the persistent FSM and permanently stop
 * reconnection. Only a protocol self-close ({@code close()}/{@code onSessionClose}) is allowed to
 * disconnect the transport. So if the regression returns, the client never reconnects and this test
 * fails at the "second ConnectionOpened" await.
 *
 * <p>The drop is induced without any production-only API: the server builder's {@code
 * serverBootstrapCustomizer} installs an acceptor-level (parent-channel) handler that captures
 * every freshly accepted child {@link Channel} into a test-held {@link ChannelGroup}. The IEC
 * pipeline is configured by the transport via {@code childHandler}, which the customizer leaves
 * untouched (it only sets {@code handler(...)} and {@code childOption(...)}), so capturing channels
 * does not break the server. Mid-session the test closes the captured child channel(s) with {@code
 * SO_LINGER=0} (hard RST), simulating a peer drop while the listening socket stays bound so the FSM
 * can reconnect to the same port.
 *
 * <p>NON-FLAKY discipline: the test asserts outcomes and event <em>ordering/counts</em>, never
 * durations. The netty-channel-fsm reconnect backoff is not tunable through any public API; it
 * starts at roughly one second, so the wait is simply bounded generously. A later phase that
 * introduces a virtual clock can make the timing deterministic; until then a real ~1s first retry
 * plus a generous settle deadline is the accepted approach.
 */
class ReconnectIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress SINGLE_POINT = PointAddress.of(1, 100);

  /**
   * Generous deadline for the FSM to notice the drop and complete a reconnect + STARTDT round trip.
   * The first reconnect attempt fires after ~1s (exponential backoff start) and is not shortenable
   * via any public knob, so this is deliberately well above {@link Await#DEFAULT_TIMEOUT}.
   */
  private static final Duration RECONNECT_TIMEOUT = Duration.ofSeconds(20);

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
  void clientAutoReconnectsAfterUnsolicitedDrop() throws Exception {
    int port = reserveEphemeralPort();
    EventCollector events = startServerAndClient(port);
    Iec60870Client client = requireNonNull(this.client);
    Iec60870Server server = requireNonNull(this.server);

    // 1. Establish the session (connect() also performs STARTDT) and prove the baseline works with
    // a real interrogation round trip.
    client.connect();
    assertTrue(client.isConnected(), "client should be connected after connect()");
    awaitCount(
        "first ConnectionOpened",
        () -> count(events, ClientEvent.ConnectionOpened.class) >= 1,
        Await.DEFAULT_TIMEOUT);
    awaitCount(
        "first DataTransferStarted",
        () -> count(events, ClientEvent.DataTransferStarted.class) >= 1,
        Await.DEFAULT_TIMEOUT);
    assertInterrogationReturnsSinglePoint(client, "baseline (pre-drop)");

    // Sanity: the acceptor handler captured the live child connection.
    assertTrue(
        acceptedChildChannels.stream().anyMatch(Channel::isActive),
        "the accepted server-side child channel should have been captured and be active");

    // 2. Abruptly drop the established connection from the server side, with NO graceful STOPDT.
    // Closing the captured child channel (SO_LINGER=0 was set on the child, so this is a hard RST)
    // simulates a peer/transport drop. The listening (acceptor) channel is left bound, so the
    // server keeps accepting and the client's persistent FSM can reconnect to the same port.
    acceptedChildChannels.close().awaitUninterruptibly();

    // 3a. The client must observe ConnectionClosed for the unsolicited drop. There is no distinct
    // ConnectionLost event: a transport-level loss maps to ConnectionClosed (with a cause present).
    // This is routed through Session.Events.onConnectionLost, the path the regression fix kept free
    // of transport.disconnect().
    awaitCount(
        "ConnectionClosed after unsolicited drop",
        () -> count(events, ClientEvent.ConnectionClosed.class) >= 1,
        RECONNECT_TIMEOUT);

    // 3b. THE load-bearing reconnect proof. The persistent, non-lazy ChannelFsm must transition
    // ChannelInactive -> ReconnectWait -> Reconnecting -> Connected on its own, WITHOUT the test
    // calling connect() again. isConnected() reflects FSM State.Connected (NettyClientTransport),
    // so its flipping back to true is direct evidence the FSM was left free to reconnect the TCP
    // socket. If the regression returns (onConnectionLost calls transport.disconnect()), the FSM
    // fires Event.Disconnect, parks in Idle/NotConnected, and isConnected() stays false forever ->
    // this await times out and the test fails. We first confirm it actually went down, then that it
    // came back up by itself, so a never-dropped connection cannot trivially satisfy the assertion.
    awaitCount(
        "transport observed the drop (FSM left Connected)",
        () -> !client.isConnected(),
        RECONNECT_TIMEOUT);
    awaitCount(
        "ChannelFsm auto-reconnected the socket (back to State.Connected) without an explicit "
            + "connect() — proves onConnectionLost did NOT disconnect the persistent transport",
        client::isConnected,
        RECONNECT_TIMEOUT);

    // 4. Prove RESUME end-to-end on the FSM-reconnected socket. The application layer re-arms the
    // IEC session (onConnected + STARTDT + a fresh ConnectionOpened) on the next connect(); because
    // the FSM is already Connected, this connect() reuses the auto-reconnected channel rather than
    // dialing a new one, so the round trip below runs over the reconnected socket. A successful
    // interrogation proves V(S)/V(R) were reset and data transfer resumed on the fresh session.
    int opensBefore = count(events, ClientEvent.ConnectionOpened.class);
    int startsBefore = count(events, ClientEvent.DataTransferStarted.class);
    client.connect();
    awaitCount(
        "a second ConnectionOpened after the reconnect (fresh IEC session armed)",
        () -> count(events, ClientEvent.ConnectionOpened.class) > opensBefore,
        Await.DEFAULT_TIMEOUT);
    awaitCount(
        "a second DataTransferStarted after the reconnect (STARTDT re-ran)",
        () -> count(events, ClientEvent.DataTransferStarted.class) > startsBefore,
        Await.DEFAULT_TIMEOUT);

    // Event ordering: the post-reconnect ConnectionOpened must follow the ConnectionClosed from the
    // drop, proving the reopen is a genuine reconnect rather than a stale duplicate of the
    // original.
    assertSecondOpenFollowsAClose(events);

    // Round-trip resume proof (strongest): a fresh interrogation terminates and returns the point.
    assertInterrogationReturnsSinglePoint(client, "post-reconnect");

    // And monitor-direction delivery resumed: a spontaneous publish reaches the client.
    int updatesBefore = count(events, ClientEvent.PointUpdated.class);
    server.publish(SINGLE_POINT, PointValue.single(true), Cause.SPONTANEOUS);
    awaitCount(
        "spontaneous PointUpdated delivered on the reconnected session",
        () ->
            events.hasMatch(
                ClientEvent.PointUpdated.class,
                u -> u.address().equals(SINGLE_POINT) && Boolean.TRUE.equals(u.value().value())),
        Await.DEFAULT_TIMEOUT);
    assertTrue(
        count(events, ClientEvent.PointUpdated.class) > updatesBefore,
        "a fresh PointUpdated should have arrived after the reconnect");

    assertTrue(client.isConnected(), "client should be connected again after the auto-reconnect");
  }

  /**
   * Asserts a station interrogation terminates and reports the single point, in the given phase.
   */
  private static void assertInterrogationReturnsSinglePoint(Iec60870Client client, String phase) {
    InterrogationResult interrogation = client.interrogate(STATION);
    assertTrue(interrogation.terminated(), phase + ": interrogation should end with ACT_TERM");
    assertEquals(STATION, interrogation.station(), phase + ": interrogation station mismatch");
    assertTrue(
        interrogation.pointValues().stream().anyMatch(p -> p.address().equals(SINGLE_POINT)),
        phase + ": single point should be returned by general interrogation");
  }

  /**
   * Asserts that the second {@link ClientEvent.ConnectionOpened} in arrival order is preceded by at
   * least one {@link ClientEvent.ConnectionClosed}, proving the reopen is a reconnect after a drop.
   */
  private static void assertSecondOpenFollowsAClose(EventCollector events) {
    List<ClientEvent> snapshot = events.events();
    int opens = 0;
    boolean sawCloseBeforeSecondOpen = false;
    for (ClientEvent event : snapshot) {
      if (event instanceof ClientEvent.ConnectionClosed && opens >= 1) {
        sawCloseBeforeSecondOpen = true;
      } else if (event instanceof ClientEvent.ConnectionOpened) {
        opens++;
        if (opens == 2) {
          assertTrue(
              sawCloseBeforeSecondOpen,
              "the second ConnectionOpened must be preceded by a ConnectionClosed (true reconnect)");
          return;
        }
      }
    }
    throw new AssertionError("expected a second ConnectionOpened in the recorded events");
  }

  private static int count(EventCollector events, Class<? extends ClientEvent> type) {
    return (int) events.events().stream().filter(type::isInstance).count();
  }

  /**
   * Bounded poll with an explicit deadline (the {@link Await} default is 10s; reconnect needs
   * more).
   */
  private static void awaitCount(String description, BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
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

  /**
   * Builds and starts a server hosting one reported single point, installs the acceptor-level child
   * capture/RST hook, then builds a client pointed at it and subscribes an {@link EventCollector}
   * before connecting.
   */
  private EventCollector startServerAndClient(int port) throws IOException {
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
                bootstrap -> {
                  // Set SO_LINGER=0 on accepted children so a later close() is a hard RST,
                  // faithfully simulating an abrupt peer/transport drop (not an orderly FIN).
                  bootstrap.childOption(ChannelOption.SO_LINGER, 0);
                  // IMPORTANT: capture via handler() on the ACCEPTOR/parent channel, never
                  // childHandler() — the transport installs the IEC pipeline through childHandler,
                  // so overriding it here would break the server. handler() is untouched.
                  bootstrap.handler(
                      new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg)
                            throws Exception {
                          if (msg instanceof Channel child) {
                            acceptedChildChannels.add(child);
                          }
                          // Let normal acceptance proceed so the IEC childHandler still runs.
                          super.channelRead(ctx, msg);
                        }
                      });
                })
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

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
