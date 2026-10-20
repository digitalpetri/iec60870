package com.digitalpetri.iec60870.test.integration;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.object.InterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueShortFloat;
import com.digitalpetri.iec60870.client.ClientConfig;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.cs104.Cs104Binding;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.transport.tcp.NettyServerTransport;
import com.digitalpetri.iec60870.transport.tcp.NettyServerTransportConfig;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Delivers large packed interrogations and spontaneous bursts over real TCP to subscribers that
 * fall behind, and checks that the client's event stream loses nothing.
 *
 * <p>The outstation is a bare {@link Cs104Binding} server session over a {@link
 * NettyServerTransport}, so its APCI layer enforces {@code k} and it never runs ahead of the
 * client's acknowledgements. It answers {@code C_IC_NA_1} with ACT_CON, {@value #GI_POINTS} {@code
 * M_ME_NC_1} objects packed 48 to an SQ=1 ASDU (full 253-octet APDUs), and ACT_TERM, then
 * immediately sends {@value #BURST_POINTS} spontaneous objects as SQ=0 ASDUs of 30. Each object's
 * value is its position in that wire order, so the test can check order as well as completeness.
 */
class LosslessEventStreamIntegrationTest {

  private static final CommonAddress CA = CommonAddress.of(1);
  private static final int GI_POINTS = 10_000;
  private static final int BURST_POINTS = 2_000;
  private static final int PACKED_PER_ASDU = 48;
  private static final int BURST_PER_ASDU = 30;
  private static final int GI_ASDUS = (GI_POINTS + PACKED_PER_ASDU - 1) / PACKED_PER_ASDU;
  private static final int BURST_ASDUS = (BURST_POINTS + BURST_PER_ASDU - 1) / BURST_PER_ASDU;
  private static final Qds GOOD = new Qds(false, false, false, false, false);

  private final ScheduledExecutorService outstationScheduler =
      Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService outstationWorker = Executors.newSingleThreadExecutor();
  private final AtomicReference<@Nullable Throwable> outstationClose = new AtomicReference<>();
  private final AtomicInteger outstationCloses = new AtomicInteger();
  private final List<AutoCloseable> closeables = new ArrayList<>();

  private @Nullable NettyServerTransport outstation;
  private @Nullable Session outstationSession;

  @AfterEach
  void tearDown() throws Exception {
    for (int i = closeables.size() - 1; i >= 0; i--) {
      closeables.get(i).close();
    }
    if (outstation != null) {
      outstation.unbind().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
    outstationWorker.shutdownNow();
    outstationScheduler.shutdownNow();
  }

  @Test
  void slowSubscriberOnCommonPoolReceivesEveryEventInWireOrder() throws Exception {
    Iec60870Client client = client(builder -> {});
    var slow = new OrderCheckingSubscriber(Duration.ofMillis(1));
    var fast = new CountingSubscriber();
    slow.reference = fast;
    client.events().subscribe(fast);
    client.events().subscribe(slow);

    runInterrogationAndBurst(client, slow);
    assertBacklogBound(slow, ClientConfig.DEFAULT_EVENT_BACKLOG_LIMIT, PACKED_PER_ASDU + 1);
  }

  @Test
  void slowSubscriberBehindABusySingleThreadExecutorReceivesEveryEvent() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    closeables.add(executor::shutdownNow);
    Iec60870Client client = client(builder -> builder.callbackExecutor(executor));
    var slow = new OrderCheckingSubscriber(Duration.ofMillis(1));
    client.events().subscribe(slow);
    client.connect();

    executor.execute(() -> LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200)));
    InterrogationResult result = client.interrogate(CA);

    assertEquals(GI_POINTS, result.objects().size());
    slow.awaitAll();
    slow.assertComplete();
    assertNoClose(slow);
  }

  @Test
  void subscriberRequestingSmallBatchesSlowsTheOutstationInsteadOfLosingEvents() throws Exception {
    int limit = 256;
    Iec60870Client client = client(builder -> builder.eventBacklogLimit(limit));
    var slow = new OrderCheckingSubscriber(Duration.ZERO);
    slow.batch = 8;
    slow.requester = outstationScheduler;
    var fast = new CountingSubscriber();
    slow.reference = fast;
    client.events().subscribe(fast);
    client.events().subscribe(slow);

    runInterrogationAndBurst(client, slow);
    assertBacklogBound(slow, limit, PACKED_PER_ASDU + 1);
  }

  @Test
  void subscriberInterrogatingFromOnNextDoesNotDeadlockThePause() throws Exception {
    Iec60870Client client = client(builder -> {});
    var result = new AtomicReference<@Nullable InterrogationResult>();
    OrderCheckingSubscriber slow =
        new OrderCheckingSubscriber(Duration.ZERO) {
          @Override
          public void onNext(ClientEvent item) {
            super.onNext(item);
            if (item instanceof ClientEvent.DataTransferStarted) {
              // The response is larger than the backlog limit and is read while this blocks.
              result.set(client.interrogate(CA));
            }
          }
        };
    client.events().subscribe(slow);
    client.connect();

    slow.awaitAll();
    assertEquals(GI_POINTS, requireNonNull(result.get()).objects().size());
    slow.assertComplete();
    assertNoClose(slow);
  }

  @Test
  void subscriberStalledLongerThanT1ClosesTheConnectionWithACause() throws Exception {
    Duration t1 = Duration.ofSeconds(2);
    ApciSettings fastTimers =
        new ApciSettings(
            UShort.valueOf(12),
            UShort.valueOf(8),
            Duration.ofSeconds(5),
            t1,
            Duration.ofSeconds(1),
            Duration.ofSeconds(20));
    startOutstation(fastTimers);
    Iec60870Client client = build(TcpIec104Client.builder().apci(fastTimers).eventBacklogLimit(64));
    var stalled = new CountDownLatch(1);
    // Teardown runs in reverse order, so a failed test releases the subscriber before closing.
    closeables.add(stalled::countDown);
    var events = new ArrayList<ClientEvent>();
    var closed = new CountDownLatch(1);
    client
        .events()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ClientEvent item) {
                if (item instanceof ClientEvent.PointUpdated) {
                  awaitUninterruptibly(stalled);
                }
                events.add(item);
                if (item instanceof ClientEvent.ConnectionClosed) {
                  closed.countDown();
                }
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
    client.connect();
    client.interrogateAsync(CA);

    Thread.sleep(t1.multipliedBy(2).toMillis());
    stalled.countDown();

    assertTrue(closed.await(30, TimeUnit.SECONDS), "ConnectionClosed delivered");
    ClientEvent last = events.get(events.size() - 1);
    Throwable cause = assertInstanceOf(ClientEvent.ConnectionClosed.class, last).cause();
    assertInstanceOf(ProtocolTimeoutException.class, cause);
  }

  // --- Scenario ----------------------------------------------------------------------------------

  private void runInterrogationAndBurst(Iec60870Client client, OrderCheckingSubscriber slow) {
    client.connect();
    InterrogationResult result = client.interrogate(CA);

    assertEquals(GI_POINTS, result.objects().size());
    slow.awaitAll();
    slow.assertComplete();
    assertNoClose(slow);
  }

  private void assertNoClose(OrderCheckingSubscriber subscriber) {
    assertEquals(0, subscriber.closes.get(), "client connection stayed open");
    assertEquals(0, outstationCloses.get(), "outstation saw no t1 or other close");
    assertNull(outstationClose.get());
  }

  private static void assertBacklogBound(OrderCheckingSubscriber slow, int limit, int perAsdu) {
    int k = ApciSettings.defaults().k().intValue();
    int bound = limit + k * perAsdu;
    assertTrue(
        slow.maxObservedBacklog <= bound,
        "backlog " + slow.maxObservedBacklog + " exceeds limit + k APDUs = " + bound);
    assertTrue(slow.maxObservedBacklog >= limit / 2, "the subscriber lagged enough to pause");
  }

  // --- Client and outstation ---------------------------------------------------------------------

  private interface Customizer {
    void customize(TcpIec104Client.Builder builder);
  }

  private Iec60870Client client(Customizer customizer) throws IOException {
    startOutstation(ApciSettings.defaults());
    TcpIec104Client.Builder builder = TcpIec104Client.builder();
    customizer.customize(builder);
    return build(builder);
  }

  private Iec60870Client build(TcpIec104Client.Builder builder) {
    Iec60870Client client = builder.host("127.0.0.1").port(port).build();
    closeables.add(client::close);
    return client;
  }

  private int port;

  private void startOutstation(ApciSettings settings) throws IOException {
    port = freePort();
    var transport =
        new NettyServerTransport(NettyServerTransportConfig.builder("127.0.0.1", port).build());
    var binding = new Cs104Binding(settings, ProtocolProfile.iec104Default());
    transport.setConnectionHandler(
        connection -> {
          Session session =
              binding.bindServer(
                  connection,
                  new OutstationEvents(),
                  outstationScheduler,
                  0,
                  OutboundQueuePolicy.DROP_OLDEST);
          outstationSession = session;
          session.onConnected();
        });
    transport.bind().toCompletableFuture().join();
    outstation = transport;
  }

  private final class OutstationEvents implements Session.Events {

    @Override
    public void onAsdu(Asdu asdu) {
      if (asdu.type() == AsduType.C_IC_NA_1 && asdu.cause() == Cause.ACTIVATION) {
        // Events run under the session lock; send from another thread.
        outstationWorker.execute(this::sendInterrogationAndBurst);
      }
    }

    private void sendInterrogationAndBurst() {
      Session session = requireNonNull(outstationSession);
      var command = new InterrogationCommand(InformationObjectAddress.of(0), qoi());
      session.sendAsdu(asdu(AsduType.C_IC_NA_1, false, Cause.ACTIVATION_CONFIRMATION, command));
      int value = 0;
      for (int start = 0; start < GI_POINTS; start += PACKED_PER_ASDU) {
        List<InformationObject> objects = new ArrayList<>();
        for (int i = start; i < Math.min(GI_POINTS, start + PACKED_PER_ASDU); i++) {
          objects.add(
              new MeasuredValueShortFloat(InformationObjectAddress.of(1 + i), value++, GOOD));
        }
        session.sendAsdu(asdu(AsduType.M_ME_NC_1, true, Cause.INTERROGATED_BY_STATION, objects));
      }
      session.sendAsdu(asdu(AsduType.C_IC_NA_1, false, Cause.ACTIVATION_TERMINATION, command));
      for (int start = 0; start < BURST_POINTS; start += BURST_PER_ASDU) {
        List<InformationObject> objects = new ArrayList<>();
        for (int i = start; i < Math.min(BURST_POINTS, start + BURST_PER_ASDU); i++) {
          objects.add(
              new MeasuredValueShortFloat(InformationObjectAddress.of(1 + i), value++, GOOD));
        }
        session.sendAsdu(asdu(AsduType.M_ME_NC_1, false, Cause.SPONTANEOUS, objects));
      }
    }

    @Override
    public void onDataTransferStateChanged(boolean started) {}

    @Override
    public void onClosed(@Nullable Throwable cause) {
      outstationCloses.incrementAndGet();
      outstationClose.set(cause);
    }

    @Override
    public void onConnectionLost(@Nullable Throwable cause) {
      // The client closing its end at teardown is expected; a lost connection mid-test shows up as
      // a client ConnectionClosed event instead.
    }
  }

  private static QualifierOfInterrogation qoi() {
    return QualifierOfInterrogation.STATION;
  }

  private static Asdu asdu(AsduType type, boolean sequence, Cause cause, InformationObject object) {
    return asdu(type, sequence, cause, List.of(object));
  }

  private static Asdu asdu(
      AsduType type, boolean sequence, Cause cause, List<InformationObject> objects) {
    return new Asdu(type, sequence, cause, false, false, OriginatorAddress.none(), CA, objects);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // --- Subscribers -------------------------------------------------------------------------------

  /** Counts every event as fast as possible, as a stand-in for the number published. */
  private static final class CountingSubscriber implements Flow.Subscriber<ClientEvent> {

    private final AtomicInteger count = new AtomicInteger();

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ClientEvent item) {
      count.incrementAndGet();
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}
  }

  /**
   * Works for a fixed time per event, optionally requesting in small batches, and checks that the
   * monitor data arrives complete and in wire order.
   */
  private static class OrderCheckingSubscriber implements Flow.Subscriber<ClientEvent> {

    private final long workNanos;
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicInteger closes = new AtomicInteger();
    private @Nullable CountingSubscriber reference;
    private long batch;
    private @Nullable ScheduledExecutorService requester;
    private Flow.@Nullable Subscription subscription;
    private long outstanding;
    private int consumed;

    private int asdus;
    private int nextAsduValue;
    private int nextPointValue;
    private int maxObservedBacklog;
    private @Nullable String failure;

    private OrderCheckingSubscriber(Duration work) {
      this.workNanos = work.toNanos();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      if (batch > 0) {
        outstanding = batch;
        subscription.request(batch);
      } else {
        subscription.request(Long.MAX_VALUE);
      }
    }

    @Override
    public void onNext(ClientEvent item) {
      if (workNanos > 0) {
        LockSupport.parkNanos(workNanos);
      }
      consumed++;
      CountingSubscriber fast = reference;
      if (fast != null) {
        maxObservedBacklog = Math.max(maxObservedBacklog, fast.count.get() - consumed);
      }
      if (item instanceof ClientEvent.AsduReceived received) {
        asdus++;
        for (InformationObject object : received.asdu().objects()) {
          if (object instanceof MeasuredValueShortFloat value) {
            check(value.value() == nextAsduValue, "AsduReceived object " + value.value());
            nextAsduValue++;
          }
        }
      } else if (item instanceof ClientEvent.PointUpdated update) {
        float value = (Float) update.value().value();
        check(value == nextPointValue, "PointUpdated " + value + ", expected " + nextPointValue);
        nextPointValue++;
        if (nextPointValue == GI_POINTS + BURST_POINTS) {
          done.countDown();
        }
      } else if (item instanceof ClientEvent.ConnectionClosed) {
        closes.incrementAndGet();
        done.countDown();
      }
      if (batch > 0 && --outstanding == 0) {
        // Ask for the next batch a little later, from another thread, like a consumer that
        // drains a bounded work queue.
        outstanding = batch;
        Flow.Subscription current = requireNonNull(subscription);
        requireNonNull(requester).schedule(() -> current.request(batch), 2, TimeUnit.MILLISECONDS);
      }
    }

    private void check(boolean condition, String message) {
      if (!condition && failure == null) {
        failure = message;
      }
    }

    private void awaitAll() {
      try {
        if (!done.await(120, TimeUnit.SECONDS)) {
          fail("timed out after " + nextPointValue + " PointUpdated events");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    private void assertComplete() {
      assertNull(failure);
      assertEquals(GI_POINTS + BURST_POINTS, nextPointValue, "every PointUpdated arrived");
      assertEquals(GI_POINTS + BURST_POINTS, nextAsduValue, "every AsduReceived object arrived");
      assertEquals(GI_ASDUS + BURST_ASDUS + 2, asdus, "every ASDU arrived");
    }

    @Override
    public void onError(Throwable throwable) {
      failure = "onError: " + throwable;
      done.countDown();
    }

    @Override
    public void onComplete() {}
  }
}
