package com.digitalpetri.iec60870.test.integration;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.ConnectionState;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.client.ReconnectPolicy;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
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
 * Real-Netty integration test that proves a {@link TcpIec104Client} re-establishes its connection
 * and its session on its own after an <em>unsolicited</em> mid-session transport drop, over a
 * loopback {@link TcpIec104Server}.
 *
 * <p>The client owns reconnect: after the drop it publishes {@link ClientEvent.DataTransferStopped}
 * and {@link ClientEvent.ConnectionClosed} with a cause, schedules an attempt per its {@link
 * ReconnectPolicy}, and on the new connection resets V(S) and V(R), runs STARTDT, and publishes
 * {@link ClientEvent.ConnectionOpened} and {@link ClientEvent.DataTransferStarted}, all without
 * another {@code connect()} call.
 *
 * <p>The drop is induced without any production-only API: the server builder's {@code
 * serverBootstrapCustomizer} installs an acceptor-level (parent-channel) handler that captures
 * every freshly accepted child {@link Channel} into a test-held {@link ChannelGroup}. Mid-session
 * the test closes the captured child channel(s) with {@code SO_LINGER=0} (hard RST), simulating a
 * peer drop while the listening socket stays bound so the client can reconnect to the same port.
 *
 * <p>The test asserts outcomes and event ordering, never durations. A short reconnect delay keeps
 * the wait brief.
 */
class ReconnectIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress SINGLE_POINT = PointAddress.of(1, 100);

  /** Generous deadline for the client to notice the drop and complete a reconnect. */
  private static final Duration RECONNECT_TIMEOUT = Duration.ofSeconds(20);

  private static final ReconnectPolicy FAST_RECONNECT =
      ReconnectPolicy.exponentialBackoff(Duration.ofMillis(100), Duration.ofSeconds(1));

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
    // server keeps accepting and the client can reconnect to the same port.
    acceptedChildChannels.close().awaitUninterruptibly();

    // 3. The client observes the drop: DataTransferStopped, then ConnectionClosed with a cause,
    // then a scheduled reconnect.
    awaitCount(
        "ConnectionClosed after unsolicited drop",
        () -> count(events, ClientEvent.ConnectionClosed.class) >= 1,
        RECONNECT_TIMEOUT);
    assertTrue(
        events.hasMatch(ClientEvent.ConnectionClosed.class, c -> c.cause() != null),
        "an unplanned close carries a cause");
    awaitCount(
        "ReconnectScheduled after the drop",
        () -> events.hasMatch(ClientEvent.ReconnectScheduled.class, r -> r.attempt() == 1),
        RECONNECT_TIMEOUT);
    assertStoppedBeforeClosed(events);

    // 4. THE load-bearing reconnect proof: with no further connect() call, the client dials a new
    // connection, re-arms the session, and runs STARTDT.
    awaitCount(
        "a second ConnectionOpened without an application call",
        () -> count(events, ClientEvent.ConnectionOpened.class) >= 2,
        RECONNECT_TIMEOUT);
    awaitCount(
        "a second DataTransferStarted without an application call (STARTDT re-ran)",
        () -> count(events, ClientEvent.DataTransferStarted.class) >= 2,
        RECONNECT_TIMEOUT);
    awaitCount("client reports CONNECTED again", client::isConnected, RECONNECT_TIMEOUT);
    assertEquals(ConnectionState.CONNECTED, client.connectionState());

    // Event ordering: the post-reconnect ConnectionOpened must follow the ConnectionClosed from
    // the drop, proving the reopen is a genuine reconnect rather than a stale duplicate of the
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

  /** Asserts that DataTransferStopped is published before the first ConnectionClosed. */
  private static void assertStoppedBeforeClosed(EventCollector events) {
    boolean stopped = false;
    for (ClientEvent event : events.events()) {
      if (event instanceof ClientEvent.DataTransferStopped) {
        stopped = true;
      } else if (event instanceof ClientEvent.ConnectionClosed) {
        assertTrue(stopped, "DataTransferStopped must precede ConnectionClosed");
        return;
      }
    }
    fail("expected a ConnectionClosed in the recorded events");
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

  /**
   * Builds and starts a server hosting one reported single point, installs the acceptor-level child
   * capture/RST hook, then builds a client pointed at it and subscribes an {@link EventCollector}
   * before connecting.
   */
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
            .reconnectPolicy(FAST_RECONNECT)
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
