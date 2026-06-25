package com.digitalpetri.iec60870.tests;

import static com.digitalpetri.iec60870.testsupport.FaultInjectingOctetTransport.Direction.CLIENT_TO_SERVER;
import static com.digitalpetri.iec60870.testsupport.FaultInjectingOctetTransport.Direction.SERVER_TO_CLIENT;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.SequenceNumberException;
import com.digitalpetri.iec60870.UnsupportedAsduTypeException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.client.ClientConfig;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.DefaultIec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.cs104.Cs104Binding;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.DefaultIec60870Server;
import com.digitalpetri.iec60870.server.InterrogationRequest;
import com.digitalpetri.iec60870.server.InterrogationResponse;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerConfig;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerEvent;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.testsupport.FaultInjectingOctetTransport;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Protocol-error and malformed-frame tests that drive a real {@link DefaultIec60870Client} and
 * {@link DefaultIec60870Server} against each other entirely in-JVM, with a {@link
 * FaultInjectingOctetTransport} between them so a single inbound frame can be corrupted at the wire
 * level without any TCP or wall-clock timing.
 *
 * <p>The assembly mirrors the production builders: a single {@link Cs104Binding} wires both the
 * client and server sessions, both facades run their callbacks on a same-thread executor ({@link
 * Runnable#run}) and share one {@link ScheduledExecutorService}, and the fault transport relays
 * frames synchronously and re-entrantly. Every fault here is triggered by an explicit control-API
 * call, so each test runs to completion on the calling thread and asserts deterministically with no
 * polling.
 *
 * <p>These tests pin two distinct outcomes that the in-JVM stack produces:
 *
 * <ul>
 *   <li>a <em>structurally valid</em> inbound I-frame whose APCI sequence number is wrong is
 *       decoded and handed to the session, which self-closes with a {@link SequenceNumberException}
 *       routed through the facade as a {@link ClientEvent.ConnectionClosed} cause (and fails any
 *       pending request);
 *   <li>a <em>structurally invalid / undecodable</em> inbound frame fails inside {@link
 *       com.digitalpetri.iec60870.cs104.ApduFramer#decode} BEFORE the session is reached. The
 *       hardened {@code Cs104Binding} listener now guards the deframe: the decode failure does not
 *       escape back out the sender's stack — instead the session is closed and the decode cause is
 *       routed through {@code Session.Events.onConnectionLost} (client: surfaced as exactly one
 *       {@link ClientEvent.ConnectionClosed}; server: surfaced as a {@link
 *       ServerEvent.ConnectionClosed} plus a {@code connection.close()} teardown), reproducing on
 *       every transport the clean drop the Netty pipeline's {@code exceptionCaught} net already
 *       performed for TCP.
 * </ul>
 *
 * <p>All teardown calls {@link FaultInjectingOctetTransport#drainAndRelease()} and closes both
 * facades so no corrupted/dropped frame is reported as a leak under the module's PARANOID detector.
 */
class ProtocolErrorFrameIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress SINGLE_POINT = PointAddress.of(1, 100);

  private final FaultInjectingOctetTransport fault = new FaultInjectingOctetTransport();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  private @Nullable DefaultIec60870Client client;
  private @Nullable DefaultIec60870Server server;

  /**
   * Captures the most recent never-completed server interrogation future (when the withholding
   * handler is installed) so teardown can release it rather than leaking it.
   */
  private final AtomicReference<@Nullable CompletableFuture<InterrogationResponse>> withheld =
      new AtomicReference<>();

  /**
   * Records every {@link ServerEvent} the server publishes, for the server-side close assertions.
   */
  private final java.util.concurrent.ConcurrentLinkedQueue<ServerEvent> serverEvents =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  @AfterEach
  void tearDown() {
    CompletableFuture<InterrogationResponse> pending = withheld.getAndSet(null);
    if (pending != null) {
      pending.cancel(false);
    }
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
    // Release any frame still parked in a hold/stall queue so a deliberately-held frame is never
    // reported as a leak by the PARANOID detector.
    fault.drainAndRelease();
    scheduler.shutdownNow();
  }

  // --- N(S) corruption: structurally valid I-frame, wrong send sequence number ------------------

  /**
   * A structurally valid inbound I-frame whose APCI send sequence number N(S) is mismatched drives
   * the client's {@code ApciSession} to self-close with a {@link SequenceNumberException}. The test
   * pins the cause-propagation the matrix flagged: the {@link ClientEvent.ConnectionClosed} carries
   * the {@code SequenceNumberException} directly as its cause, AND a request that was pending when
   * the corruption arrived fails with a {@link ConnectionClosedException} whose cause is that same
   * {@code SequenceNumberException}.
   */
  @Test
  void corruptInboundISequenceNumberClosesClientWithSequenceErrorAndFailsPending() {
    EventCollector events = startWithholdingServerAndConnectedClient();
    DefaultIec60870Client client = requireNonNull(this.client);
    DefaultIec60870Server server = requireNonNull(this.server);

    // A request the server will never confirm, so it is genuinely pending when the corruption
    // lands.
    CompletableFuture<InterrogationResult> pending =
        client.interrogateAsync(STATION).toCompletableFuture();
    assertFalse(pending.isDone(), "interrogation must still be pending before the corruption");

    // Corrupt the NEXT inbound (server->client) I-frame's N(S): flip bit 1 of control octet 0
    // (frame byte 2), which advances N(S) by one while keeping bit 0 = 0 so it stays an I-frame.
    // Then make the server emit exactly one spontaneous monitor I-frame to trigger it.
    fault.corruptNext(SERVER_TO_CLIENT, flipBit(2, 0x02));
    server.publish(SINGLE_POINT, PointValue.single(true), Cause.SPONTANEOUS);

    // (a) the pending request failed with ConnectionClosedException caused by
    // SequenceNumberException.
    ExecutionException failure =
        assertThrows(
            ExecutionException.class, pending::get, "pending request must fail on the close");
    ConnectionClosedException closed =
        assertInstanceOf(ConnectionClosedException.class, failure.getCause());
    assertInstanceOf(
        SequenceNumberException.class,
        closed.getCause(),
        "the pending failure's root cause must be the sequence-number violation");

    // (b) exactly one ConnectionClosed, and its cause is the SequenceNumberException itself (raw,
    // not wrapped) — the cause-propagation the matrix calls out.
    List<ClientEvent.ConnectionClosed> closeEvents =
        events.events().stream()
            .filter(ClientEvent.ConnectionClosed.class::isInstance)
            .map(ClientEvent.ConnectionClosed.class::cast)
            .toList();
    assertEquals(1, closeEvents.size(), "exactly one ConnectionClosed must be published");
    assertInstanceOf(
        SequenceNumberException.class,
        closeEvents.get(0).cause(),
        "ConnectionClosed cause must be the SequenceNumberException itself");

    // (c) onClosed (the self-close path) called transport.disconnect(), so the client is down.
    assertFalse(client.isConnected(), "client transport must be disconnected after the self-close");
  }

  // --- N(R) corruption: invalid acknowledgement -------------------------------------------------

  /**
   * A structurally valid inbound I-frame whose acknowledgement N(R) acknowledges more frames than
   * the client has outstanding drives the second sequence-validation site ({@code
   * processReceiveSequenceNumber}) to self-close, again with a {@link SequenceNumberException}
   * surfaced as the {@link ClientEvent.ConnectionClosed} cause.
   */
  @Test
  void corruptInboundAcknowledgementClosesClientWithSequenceError() {
    EventCollector events = startDefaultServerAndConnectedClient();
    DefaultIec60870Client client = requireNonNull(this.client);
    DefaultIec60870Server server = requireNonNull(this.server);

    // Corrupt the next inbound I-frame's N(R): force control octet 2 (frame byte 4) high so the
    // acknowledged count far exceeds anything the client could have outstanding, tripping the
    // "invalid acknowledgement N(R)" guard. Trigger with one spontaneous monitor I-frame.
    fault.corruptNext(SERVER_TO_CLIENT, set(4, 0xFE));
    server.publish(SINGLE_POINT, PointValue.single(true), Cause.SPONTANEOUS);

    List<ClientEvent.ConnectionClosed> closeEvents =
        events.events().stream()
            .filter(ClientEvent.ConnectionClosed.class::isInstance)
            .map(ClientEvent.ConnectionClosed.class::cast)
            .toList();
    assertEquals(1, closeEvents.size(), "an unexpected N(R) must close the client exactly once");
    assertInstanceOf(
        SequenceNumberException.class,
        closeEvents.get(0).cause(),
        "the close cause must be a SequenceNumberException for the bad acknowledgement");
    assertFalse(client.isConnected(), "client transport must be disconnected after the self-close");
  }

  // --- Truncated / garbled whole frame: decode failure drops the connection cleanly -------------

  /**
   * A truncated/garbled inbound whole frame fails inside {@code ApduFramer.decode} before the
   * session is reached. The hardened {@code Cs104Binding} listener now guards the deframe: instead
   * of letting the {@link AsduDecodeException} escape synchronously back out the SERVER's {@code
   * publish(...)} stack, it closes the client session and routes the decode cause through {@code
   * Session.Events.onConnectionLost}, which the facade surfaces as exactly one {@link
   * ClientEvent.ConnectionClosed} carrying that cause (and fails any pending request with a {@link
   * ConnectionClosedException} whose cause is the decode failure). This is the same observable
   * outcome the Netty pipeline's {@code exceptionCaught} net produces, now reproduced on every
   * transport.
   *
   * <p>On the in-JVM fault transport the client-role close handle tears down the current pair but
   * does not model reconnection; the production Netty transport uses the same close-current-channel
   * hook while leaving its persistent FSM free to reconnect.
   */
  @Test
  void garbledInboundWholeFrameDecodeFailureDropsConnectionCleanly() {
    EventCollector events = startWithholdingServerAndConnectedClient();
    DefaultIec60870Client client = requireNonNull(this.client);
    DefaultIec60870Server server = requireNonNull(this.server);

    // A request the server will never confirm, so it is genuinely pending when the corrupted frame
    // lands and the decode failure closes the session.
    CompletableFuture<InterrogationResult> pending =
        client.interrogateAsync(STATION).toCompletableFuture();
    assertFalse(pending.isDone(), "interrogation must still be pending before the corruption");

    // Corrupt the START octet (frame byte 0) of the next inbound frame to a non-0x68 value, so
    // Apdu.Serde.decode throws an AsduDecodeException at the very first check.
    fault.corruptNext(SERVER_TO_CLIENT, set(0, 0x00));

    // The decode failure is now swallowed inside the binding's onFrame; sending the triggering
    // frame no longer throws.
    server.publish(SINGLE_POINT, PointValue.single(true), Cause.SPONTANEOUS);

    // (a) exactly one ConnectionClosed, whose cause is the START-octet AsduDecodeException routed
    // through events.onConnectionLost.
    List<ClientEvent.ConnectionClosed> closeEvents =
        events.events().stream()
            .filter(ClientEvent.ConnectionClosed.class::isInstance)
            .map(ClientEvent.ConnectionClosed.class::cast)
            .toList();
    assertEquals(1, closeEvents.size(), "the malformed frame must close the session exactly once");
    AsduDecodeException closeCause =
        assertInstanceOf(
            AsduDecodeException.class,
            closeEvents.get(0).cause(),
            "ConnectionClosed cause must be the decode failure");
    assertNotNull(closeCause);
    assertTrue(
        closeCause.getMessage().contains("START"),
        "the close cause must be the START-octet check: " + closeCause.getMessage());

    // (b) the pending request failed with ConnectionClosedException caused by the decode failure.
    ExecutionException failure =
        assertThrows(
            ExecutionException.class, pending::get, "pending request must fail on the close");
    ConnectionClosedException closed =
        assertInstanceOf(ConnectionClosedException.class, failure.getCause());
    assertInstanceOf(
        AsduDecodeException.class,
        closed.getCause(),
        "the pending failure's root cause must be the decode failure");

    // (c) the client binding also closed the underlying current connection, so the transport no
    // longer reports connected after the malformed frame.
    assertFalse(
        client.isConnected(),
        "client transport must be disconnected after a client-side decode failure");
  }

  // --- Server-side malformed TypeID: decodable-but-undispatched vs undecodable -------------------

  /**
   * CASE A — a fully decodable inbound ASDU whose TypeID has no server dispatch path. The client
   * sends a real {@code M_EI_NA_1} (end-of-initialization, id 70) ASDU; the server decodes it,
   * falls through to {@code handleUnknown}, and mirrors it back NEGATIVE with cause {@code
   * UNKNOWN_TYPE_ID}. The negative mirror reaches the client as an {@link ClientEvent.AsduReceived}
   * and the connection stays up. This is the graceful, in-spec path, complementing the app-level
   * {@code unknownTypeIsMirroredNegative} unit test at the wire level.
   */
  @Test
  void serverMirrorsDecodableUndispatchedTypeNegativeAndStaysConnected() {
    EventCollector events = startDefaultServerAndConnectedClient();
    DefaultIec60870Client client = requireNonNull(this.client);

    // Rewrite the next client->server I-frame's TypeID (frame byte 6) to M_EI_NA_1 (70), a valid
    // type with no server dispatch path, while keeping it structurally decodable (one information
    // object). HOLD it: the fault relay is synchronous and re-entrant, and the session advances
    // its send sequence number only AFTER output.send returns, so delivering the corrupted frame
    // inline would let the server's synchronous mirror reply (carrying N(R)=1) arrive before the
    // client's V(S) is incremented and spuriously trip the acknowledgement guard. Holding and
    // releasing the frame after interrogateAsync returns delivers it (and the synchronous reply)
    // only once the client's V(S) reflects the sent frame.
    fault.holdNext(CLIENT_TO_SERVER);
    fault.corruptNext(CLIENT_TO_SERVER, set(6, AsduType.M_EI_NA_1.typeId()));
    CompletableFuture<InterrogationResult> request =
        client.interrogateAsync(STATION).toCompletableFuture();
    fault.release(CLIENT_TO_SERVER);

    // The server mirrored the unknown type back NEGATIVE; the client sees it as an AsduReceived
    // with P/N set and cause UNKNOWN_TYPE_ID.
    assertTrue(
        events.events().stream()
            .filter(ClientEvent.AsduReceived.class::isInstance)
            .map(ClientEvent.AsduReceived.class::cast)
            .anyMatch(e -> e.asdu().negative() && e.asdu().cause() == Cause.UNKNOWN_TYPE_ID),
        "client must receive a NEGATIVE UNKNOWN_TYPE_ID mirror for the undispatched type");

    // The connection stays up: no close was published and the client is still connected.
    assertFalse(
        events.events().stream().anyMatch(ClientEvent.ConnectionClosed.class::isInstance),
        "a decodable-but-undispatched type must not close the connection");
    assertTrue(client.isConnected(), "client must remain connected after the negative mirror");

    // The interrogation itself is never confirmed; release it explicitly.
    request.cancel(false);
  }

  /**
   * CASE B — an inbound ASDU whose TypeID byte is UNDEFINED (no {@link AsduType} constant). The
   * server's inbound decode fails in {@code Asdu.Serde.decode} via {@code AsduType.fromId},
   * throwing an {@link UnsupportedAsduTypeException}. The hardened {@code Cs104Binding} listener
   * now guards the deframe: instead of letting that exception unwind synchronously back out the
   * CLIENT's send stack, the SERVER binding closes its session, routes the decode cause through
   * {@code Session.Events.onConnectionLost} (surfaced as a server-side {@link
   * ServerEvent.ConnectionClosed} carrying the {@code UnsupportedAsduTypeException}), and tears the
   * accepted connection down via {@code connection.close()}.
   *
   * <p>The net new behavior: the client's triggering send no longer throws (the server swallows the
   * decode failure), so the interrogation simply stays pending and the SERVER cleanly closes the
   * connection with the decode cause. No negative mirror is emitted (decode fails before dispatch).
   * On this in-JVM fault transport the connection close notifies only the server leg, so the client
   * does not observe the loss here — the wire-level loss propagation is covered by the real-Netty
   * close-path tests; this test pins the server-side clean teardown the binding now performs.
   */
  @Test
  void serverUndecodableTypeIdClosesServerConnectionCleanlyAndIsNotMirrored() {
    EventCollector events = startDefaultServerAndConnectedClient();
    DefaultIec60870Client client = requireNonNull(this.client);

    // Rewrite the TypeID byte (frame byte 6) of the next client->server I-frame to 41, which is an
    // undefined type identification, so AsduType.fromId throws UnsupportedAsduTypeException during
    // the server's inbound decode.
    fault.corruptNext(CLIENT_TO_SERVER, set(6, 41));

    // The server binding now swallows the decode failure inside onFrame; the client's triggering
    // send no longer throws, so the interrogation just stays pending.
    CompletableFuture<InterrogationResult> request =
        client.interrogateAsync(STATION).toCompletableFuture();
    assertFalse(
        request.isDone(),
        "the interrogation stays pending: the decode failure no longer unwinds into the send");

    // The SERVER tore the connection down cleanly: exactly one server-side ConnectionClosed carries
    // the UnsupportedAsduTypeException that names the undefined type id.
    List<ServerEvent.ConnectionClosed> serverCloses =
        serverEvents.stream()
            .filter(ServerEvent.ConnectionClosed.class::isInstance)
            .map(ServerEvent.ConnectionClosed.class::cast)
            .toList();
    assertEquals(
        1,
        serverCloses.size(),
        "the server must close the connection exactly once on the bad type");
    UnsupportedAsduTypeException cause =
        assertInstanceOf(
            UnsupportedAsduTypeException.class,
            serverCloses.get(0).cause(),
            "the server close cause must be the UnsupportedAsduTypeException");
    assertNotNull(cause);
    assertTrue(
        cause.getMessage().contains("41"),
        "the failure must name the undefined type id: " + cause.getMessage());

    // No negative mirror is emitted: decode fails before dispatch, so the client sees neither an
    // AsduReceived nor a client-side ConnectionClosed on this in-JVM transport.
    assertFalse(
        events.events().stream().anyMatch(ClientEvent.AsduReceived.class::isInstance),
        "no negative mirror is emitted for an undecodable type id (decode fails before dispatch)");
    assertFalse(
        events.events().stream().anyMatch(ClientEvent.ConnectionClosed.class::isInstance),
        "the in-JVM connection close notifies only the server leg, not the client");

    // The interrogation is never confirmed (the connection was torn down); release it explicitly.
    request.cancel(false);
  }

  // --- Wiring helpers ---------------------------------------------------------------------------

  /** Mutator that XORs {@code mask} into frame byte {@code index} (e.g. to flip a sequence bit). */
  private static UnaryOperator<ByteBuf> flipBit(int index, int mask) {
    return copy -> {
      copy.setByte(index, copy.getByte(index) ^ mask);
      return copy;
    };
  }

  /** Mutator that overwrites frame byte {@code index} with {@code value}. */
  private static UnaryOperator<ByteBuf> set(int index, int value) {
    return copy -> {
      copy.setByte(index, value);
      return copy;
    };
  }

  /**
   * Builds the in-JVM client+server pair over the fault transport with a default (auto-answering)
   * handler, connects (and starts data transfer), and returns the client's event collector.
   */
  private EventCollector startDefaultServerAndConnectedClient() {
    return startAndConnect(new ServerHandler() {});
  }

  /**
   * Builds the pair with a handler whose interrogation never completes (captured for teardown), so
   * a client interrogation stays genuinely pending. Connects and returns the event collector.
   */
  private EventCollector startWithholdingServerAndConnectedClient() {
    return startAndConnect(
        new ServerHandler() {
          @Override
          public CompletionStage<InterrogationResponse> onInterrogationAsync(
              ServerContext context, InterrogationRequest request) {
            CompletableFuture<InterrogationResponse> future = new CompletableFuture<>();
            withheld.set(future);
            return future; // never completed -> the client's interrogation stays pending.
          }
        });
  }

  /**
   * Mirrors the production assembly (TcpIec104Client/Server delegating to {@link Cs104Binding}) but
   * over the in-JVM fault transport, with a same-thread callback executor and one shared scheduler
   * so the whole relay is deterministic and synchronous. Connects the client (which performs
   * STARTDT) and returns its subscribed {@link EventCollector}.
   */
  private EventCollector startAndConnect(ServerHandler handler) {
    Cs104Binding binding =
        new Cs104Binding(ApciSettings.defaults(), ProtocolProfile.iec104Default());

    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    SINGLE_POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED))
            .build();

    ServerConfig serverConfig =
        ServerConfig.builder()
            .station(station)
            .handler(handler)
            .callbackExecutor(Runnable::run)
            .build();
    DefaultIec60870Server server =
        new DefaultIec60870Server(
            fault.server(),
            serverConfig,
            (connection, events, sched) ->
                binding.bindServer(
                    connection,
                    events,
                    sched,
                    serverConfig.maxOutboundQueue(),
                    serverConfig.eventQueuePolicy()),
            scheduler);
    server.start();
    this.server = server;
    server
        .events()
        .subscribe(
            new java.util.concurrent.Flow.Subscriber<>() {
              @Override
              public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ServerEvent item) {
                serverEvents.add(item);
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });

    ClientConfig clientConfig =
        ClientConfig.builder()
            .sessionSettings(ApciSettings.defaults())
            .startDataTransferOnConnect(true)
            .callbackExecutor(Runnable::run)
            .build();
    DefaultIec60870Client client =
        new DefaultIec60870Client(
            fault.client(),
            clientConfig,
            (events, sched) -> binding.bindClient(fault.client(), events, sched),
            scheduler);
    this.client = client;

    EventCollector events = new EventCollector();
    client.events().subscribe(events);
    client.connect();
    assertTrue(client.isConnected(), "client should be connected after connect()");
    return events;
  }
}
