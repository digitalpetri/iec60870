package com.digitalpetri.iec60870.test.integration;

import static com.digitalpetri.iec60870.test.common.FaultInjectingOctetTransport.Direction.CLIENT_TO_SERVER;
import static com.digitalpetri.iec60870.test.common.FaultInjectingOctetTransport.Direction.SERVER_TO_CLIENT;
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
import com.digitalpetri.iec60870.test.common.FaultInjectingOctetTransport;
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
 *   <li>a <em>structurally invalid</em> inbound frame (a framing error) fails inside {@link
 *       com.digitalpetri.iec60870.cs104.ApduFramer#decodeHeader} BEFORE the session is reached. The
 *       {@code Cs104Binding} listener closes the session and routes the decode cause through {@code
 *       Session.Events.onConnectionLost} (client: surfaced as exactly one {@link
 *       ClientEvent.ConnectionClosed}; server: surfaced as a {@link ServerEvent.ConnectionClosed}
 *       plus a {@code connection.close()} teardown);
 *   <li>a correctly framed I-frame whose <em>ASDU</em> cannot be decoded is acknowledged and
 *       reported as an undecodable ASDU, and the connection stays open. The server answers an
 *       undefined TypeID or cause with a negative mirror.
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

    // Withhold the response so the request is pending when its first reply is corrupted.
    CompletableFuture<InterrogationResult> pending =
        client.interrogateAsync(STATION).toCompletableFuture();
    assertFalse(pending.isDone(), "interrogation must still be pending before the corruption");

    // Corrupt the NEXT inbound (server->client) I-frame's N(S): flip bit 1 of control octet 0
    // (frame byte 2), which advances N(S) by one while keeping bit 0 = 0 so it stays an I-frame.
    // Release the handler to emit ACT_CON as the next I-frame. Publications wait behind the GI.
    fault.corruptNext(SERVER_TO_CLIENT, flipBit(2, 0x02));
    requireNonNull(withheld.get()).complete(InterrogationResponse.of(List.of()));

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
   * <p>On the in-JVM fault transport the client-role close handle tears down the current pair; the
   * production Netty transport uses the same close-current-channel hook, and in both cases the
   * client facade decides whether to reconnect.
   */
  @Test
  void garbledInboundWholeFrameDecodeFailureDropsConnectionCleanly() {
    EventCollector events = startWithholdingServerAndConnectedClient();
    DefaultIec60870Client client = requireNonNull(this.client);

    // Withhold the response so the request is pending when its first reply is corrupted.
    CompletableFuture<InterrogationResult> pending =
        client.interrogateAsync(STATION).toCompletableFuture();
    assertFalse(pending.isDone(), "interrogation must still be pending before the corruption");

    // Corrupt the START octet (frame byte 0) of the next inbound frame to a non-0x68 value, so
    // Apdu.Serde.decode throws an AsduDecodeException at the very first check.
    fault.corruptNext(SERVER_TO_CLIENT, set(0, 0x00));

    // The decode failure is now swallowed inside the binding's onFrame; sending the triggering
    // frame no longer throws.
    requireNonNull(withheld.get()).complete(InterrogationResponse.of(List.of()));

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
   * frame is correctly framed, so the server session acknowledges it and reports the raw octets as
   * a {@link ServerEvent.UndecodableAsdu} carrying the {@link UnsupportedAsduTypeException}. The
   * server answers with the octets mirrored back NEGATIVE with cause {@code UNKNOWN_TYPE_ID} (IEC
   * 60870-5-101 7.2.3), and neither side closes the connection. The client cannot decode the mirror
   * either (its TypeID is still 41), so it reports it as a {@link ClientEvent.UndecodableAsdu}.
   */
  @Test
  void serverMirrorsUndecodableTypeIdNegativeAndStaysConnected() {
    EventCollector events = startDefaultServerAndConnectedClient();
    DefaultIec60870Client client = requireNonNull(this.client);

    // Rewrite the TypeID byte (frame byte 6) of the next client->server I-frame to 41, an undefined
    // type identification. Hold and release it for the same reason as CASE A: the server's mirror
    // is sent synchronously and must not overtake the client's V(S) increment.
    fault.holdNext(CLIENT_TO_SERVER);
    fault.corruptNext(CLIENT_TO_SERVER, set(6, 41));
    CompletableFuture<InterrogationResult> request =
        client.interrogateAsync(STATION).toCompletableFuture();
    fault.release(CLIENT_TO_SERVER);

    List<ServerEvent.UndecodableAsdu> undecodable =
        serverEvents.stream()
            .filter(ServerEvent.UndecodableAsdu.class::isInstance)
            .map(ServerEvent.UndecodableAsdu.class::cast)
            .toList();
    assertEquals(1, undecodable.size(), "the server must report the undecodable ASDU once");
    assertEquals(41, undecodable.get(0).asdu()[0]);
    UnsupportedAsduTypeException cause =
        assertInstanceOf(UnsupportedAsduTypeException.class, undecodable.get(0).cause());
    assertTrue(
        cause.getMessage().contains("41"),
        "the failure must name the undefined type id: " + cause.getMessage());
    assertFalse(
        serverEvents.stream().anyMatch(ServerEvent.AsduReceived.class::isInstance),
        "an undecodable ASDU must not be published as AsduReceived");

    // The mirror reached the client: same octets, COT 44 with P/N set.
    List<ClientEvent.UndecodableAsdu> mirrors =
        events.events().stream()
            .filter(ClientEvent.UndecodableAsdu.class::isInstance)
            .map(ClientEvent.UndecodableAsdu.class::cast)
            .toList();
    assertEquals(1, mirrors.size(), "the client must receive exactly one negative mirror");
    byte[] mirror = mirrors.get(0).asdu();
    byte[] received = undecodable.get(0).asdu();
    assertEquals(41, mirror[0]);
    assertEquals(0x40 | Cause.UNKNOWN_TYPE_ID.value(), mirror[2] & 0xFF);
    assertEquals(received.length, mirror.length);
    for (int i = 0; i < received.length; i++) {
      if (i != 2) {
        assertEquals(received[i], mirror[i], "mirror octet " + i + " must match the request");
      }
    }

    assertFalse(
        serverEvents.stream().anyMatch(ServerEvent.ConnectionClosed.class::isInstance),
        "an undecodable ASDU must not close the server connection");
    assertFalse(
        events.events().stream().anyMatch(ClientEvent.ConnectionClosed.class::isInstance),
        "an undecodable mirror must not close the client connection");
    assertTrue(client.isConnected(), "client must remain connected");

    // The interrogation is never confirmed; release it explicitly.
    request.cancel(false);
  }

  /**
   * CASE C — an inbound ASDU with a known TypeID but an UNDEFINED cause of transmission. The server
   * acknowledges and reports it, then mirrors it NEGATIVE with cause {@code UNKNOWN_CAUSE}. That
   * mirror is decodable, so the client sees it as an ordinary negative {@link
   * ClientEvent.AsduReceived}.
   */
  @Test
  void serverMirrorsUndefinedCauseNegativeAndStaysConnected() {
    EventCollector events = startDefaultServerAndConnectedClient();
    DefaultIec60870Client client = requireNonNull(this.client);

    // Rewrite the cause of transmission (frame byte 8) to 48, the first private-range cause.
    fault.holdNext(CLIENT_TO_SERVER);
    fault.corruptNext(CLIENT_TO_SERVER, set(8, 48));
    CompletableFuture<InterrogationResult> request =
        client.interrogateAsync(STATION).toCompletableFuture();
    fault.release(CLIENT_TO_SERVER);

    ServerEvent.UndecodableAsdu undecodable =
        serverEvents.stream()
            .filter(ServerEvent.UndecodableAsdu.class::isInstance)
            .map(ServerEvent.UndecodableAsdu.class::cast)
            .findFirst()
            .orElseThrow();
    assertInstanceOf(AsduDecodeException.class, undecodable.cause());

    assertTrue(
        events.events().stream()
            .filter(ClientEvent.AsduReceived.class::isInstance)
            .map(ClientEvent.AsduReceived.class::cast)
            .anyMatch(
                e ->
                    e.asdu().type() == AsduType.C_IC_NA_1
                        && e.asdu().negative()
                        && e.asdu().cause() == Cause.UNKNOWN_CAUSE),
        "client must receive a NEGATIVE UNKNOWN_CAUSE mirror");
    assertFalse(
        serverEvents.stream().anyMatch(ServerEvent.ConnectionClosed.class::isInstance),
        "an undefined cause must not close the server connection");
    assertTrue(client.isConnected(), "client must remain connected");

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
