package com.digitalpetri.iec60870.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.EventBacklogExceededException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.UnsupportedAsduTypeException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueScaled;
import com.digitalpetri.iec60870.fakes.FakeClientTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests the client's lossless event stream and its inbound flow control over a fake session. */
class ClientEventFlowControlTest {

  private static final CommonAddress STATION = CommonAddress.of(1);

  /** Runs event deliveries only when the test drains it, so backlogs build deterministically. */
  private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

  private final FakeClientTransport transport = new FakeClientTransport();
  private final AtomicReference<@Nullable FakeSession> sessionRef = new AtomicReference<>();
  private final List<ClientEvent> received = new ArrayList<>();
  private @Nullable DefaultIec60870Client client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  private DefaultIec60870Client connect(int backlogLimit) {
    DefaultIec60870Client client =
        new DefaultIec60870Client(
            transport,
            ClientConfig.builder()
                .callbackExecutor(tasks::add)
                .eventBacklogLimit(backlogLimit)
                .build(),
            (events, scheduler) -> {
              FakeSession session = FakeSession.client(events);
              sessionRef.set(session);
              return session;
            },
            new ManualScheduler());
    this.client = client;
    client.events().subscribe(new CollectingSubscriber());
    client.connect();
    return client;
  }

  private FakeSession session() {
    return requireNonNull(sessionRef.get());
  }

  private void drain() {
    Runnable task;
    while ((task = tasks.poll()) != null) {
      task.run();
    }
  }

  @Test
  void laggingSubscriberPausesAndResumesInboundData() {
    connect(10);
    drain();

    for (int i = 0; i < 4; i++) {
      session().deliverAsdu(measured(i));
    }
    assertEquals(List.of(), session().inboundPauses());

    // AsduReceived + PointUpdated per ASDU: the fifth ASDU brings the backlog to the limit.
    session().deliverAsdu(measured(4));
    assertEquals(List.of(true), session().inboundPauses());

    drain();
    assertEquals(List.of(true, false), session().inboundPauses());
    assertEquals(5, count(ClientEvent.PointUpdated.class));
  }

  @Test
  void lifecycleEventsArriveBehindABacklogAboveTheLimit() {
    connect(10);
    drain();
    received.clear();

    for (int i = 0; i < 50; i++) {
      session().deliverAsdu(measured(i));
    }
    var cause = new ProtocolTimeoutException("t1");
    session().fireClosed(cause);

    drain();
    assertEquals(50, count(ClientEvent.AsduReceived.class));
    assertEquals(50, count(ClientEvent.PointUpdated.class));
    // The close follows every data event, then the default policy schedules a reconnect.
    int size = received.size();
    assertInstanceOf(ClientEvent.DataTransferStopped.class, received.get(size - 3));
    ClientEvent closed = received.get(size - 2);
    assertEquals(cause, assertInstanceOf(ClientEvent.ConnectionClosed.class, closed).cause());
    ClientEvent scheduled = received.get(size - 1);
    assertEquals(cause, assertInstanceOf(ClientEvent.ReconnectScheduled.class, scheduled).cause());
  }

  @Test
  void newConnectionStartsUnpausedAndPausesAgainIfStillBehind() {
    DefaultIec60870Client client = connect(10);
    for (int i = 0; i < 5; i++) {
      session().deliverAsdu(measured(i));
    }
    assertEquals(List.of(true), session().inboundPauses());
    session().fireConnectionLost(null);

    client.connect();
    session().deliverAsdu(measured(5));

    assertEquals(List.of(true, true), session().inboundPauses());
  }

  @Test
  void backlogAtHardCapacityClosesConnectionWithoutAGap() {
    connect(1);
    int delivered = 0;
    while (!session().isClosed() && delivered < 20_000) {
      session().deliverAsdu(measured(delivered));
      delivered++;
    }
    assertTrue(session().isClosed(), "the client closes the session at the hard capacity");
    // ASDUs that arrive while the close is in progress are discarded, not delivered with a gap.
    session().deliverAsdu(measured(delivered));
    assertEquals(1, transport.disconnectCount());
    assertTrue(session().inboundPauses().contains(true));

    drain();
    List<ClientEvent.PointUpdated> updates = new ArrayList<>();
    for (ClientEvent event : received) {
      if (event instanceof ClientEvent.PointUpdated update) {
        updates.add(update);
      }
    }
    // The last ASDU offered found no room; every ASDU before it was delivered in order.
    assertEquals(delivered - 1, updates.size());
    for (int i = 0; i < updates.size(); i++) {
      assertEquals((short) i, updates.get(i).value().value());
    }
    ClientEvent closed = received.get(received.size() - 2);
    assertInstanceOf(
        EventBacklogExceededException.class,
        assertInstanceOf(ClientEvent.ConnectionClosed.class, closed).cause());
    assertInstanceOf(ClientEvent.ReconnectScheduled.class, received.get(received.size() - 1));
  }

  @Test
  void lifecycleOperationsAreRefusedUntilASubscriberAtCapacityCatchesUp() {
    DefaultIec60870Client client = connect(1);
    int delivered = 0;
    while (!session().isClosed() && delivered < 20_000) {
      session().deliverAsdu(measured(delivered++));
    }

    // Without the refusal, repeated reconnects would keep publishing lifecycle events into the
    // reserve until it overflowed.
    for (int i = 0; i < 2 * EventStream.LIFECYCLE_RESERVE; i++) {
      EventBacklogExceededException e =
          assertThrows(EventBacklogExceededException.class, client::connect);
      assertTrue(e.getMessage().contains("connect"), e.getMessage());
    }
    assertThrows(EventBacklogExceededException.class, client::startDataTransfer);
    assertThrows(EventBacklogExceededException.class, client::stopDataTransfer);

    drain();
    assertEquals(1, count(ClientEvent.ConnectionOpened.class));
    assertEquals(1, count(ClientEvent.ConnectionClosed.class), "the backlog close was delivered");

    client.connect();
    drain();
    assertEquals(2, count(ClientEvent.ConnectionOpened.class), "the reconnect was admitted");
  }

  @Test
  void undecodableAsduCountsTowardTheBacklog() {
    connect(4);
    drain();

    for (int i = 0; i < 4; i++) {
      session()
          .deliverUndecodableAsdu(
              new byte[] {(byte) 200, 1, 3, 0, 1, 0},
              new UnsupportedAsduTypeException("private type"));
    }
    assertEquals(List.of(true), session().inboundPauses());

    drain();
    assertEquals(4, count(ClientEvent.UndecodableAsdu.class));
  }

  private long count(Class<? extends ClientEvent> type) {
    return received.stream().filter(type::isInstance).count();
  }

  private static Asdu measured(int value) {
    return new Asdu(
        AsduType.M_ME_NB_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        STATION,
        List.of(
            new MeasuredValueScaled(
                InformationObjectAddress.of(110),
                (short) value,
                new Qds(false, false, false, false, false))));
  }

  private final class CollectingSubscriber implements Flow.Subscriber<ClientEvent> {

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ClientEvent item) {
      received.add(item);
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}
  }
}
