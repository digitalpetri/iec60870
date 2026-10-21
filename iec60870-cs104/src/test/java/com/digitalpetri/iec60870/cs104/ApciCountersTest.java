package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.ProtocolStateException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.SequenceNumberException;
import com.digitalpetri.iec60870.diagnostics.Direction;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ApciCountersTest {

  private static final InetSocketAddress REMOTE = InetSocketAddress.createUnresolved("rtu", 2404);

  @Test
  void eachCallbackIncrementsItsCounter() {
    var counters = new ApciCounters();

    counters.onApduSent(new ControlField.TypeI(0, 0), 16);
    counters.onApduSent(new ControlField.TypeS(1), 6);
    counters.onApduSent(new ControlField.TypeU(UFunction.TESTFR_ACT), 6);
    counters.onApduSent(new ControlField.TypeU(UFunction.STARTDT_ACT), 6);
    counters.onApduReceived(new ControlField.TypeI(0, 0), 20);
    counters.onApduReceived(new ControlField.TypeU(UFunction.TESTFR_CON), 6);
    counters.onTimerExpired(ApciTimer.T1);
    counters.onTimerExpired(ApciTimer.T2);
    counters.onTimerExpired(ApciTimer.T2);
    counters.onTimerExpired(ApciTimer.T3);
    counters.onWindowFull(3);
    counters.onFrameIgnored(UFunction.TESTFR_CON, "unsolicited");
    counters.onProtocolError(new SequenceNumberException("ns"));
    counters.onProtocolError(new ProtocolStateException("stopped"));
    counters.onProtocolError(new ProtocolTimeoutException("t1"));
    counters.onDecodeError(Unpooled.EMPTY_BUFFER, new IllegalArgumentException());
    counters.onConnectAttempt(REMOTE);
    counters.onConnectFailed(REMOTE, new ConnectionClosedException("refused"));
    counters.onConnected(REMOTE, REMOTE);
    counters.onConnectionAccepted(REMOTE);
    counters.onConnectionClosed(null);
    counters.onReconnectScheduled(1, Duration.ofSeconds(1), new ConnectionClosedException("x"));
    counters.onInboundPaused(true);
    counters.onInboundPaused(false);
    counters.onEventDropped(String.class);
    counters.onAsduDropped(Direction.IN, "backlog");
    counters.onAsduDropped(Direction.OUT, "queue");
    counters.onAsduDropped(Direction.OUT, "queue");

    assertEquals(
        new ApciCounters.Snapshot(
            1, 1, 1, 0, 2, 1, 34, 26, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 2),
        counters.snapshot());
  }

  @Test
  void sharedInstanceLosesNoUpdatesUnderConcurrency() throws InterruptedException {
    var counters = new ApciCounters();
    int threads = 8;
    int iterations = 20_000;
    var start = new CountDownLatch(1);
    var stop = new AtomicBoolean();
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      Thread worker =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  return;
                }
                for (int i = 0; i < iterations; i++) {
                  counters.onApduSent(new ControlField.TypeI(i % 32768, 0), 16);
                  counters.onApduReceived(new ControlField.TypeS(0), 6);
                  counters.onTimerExpired(ApciTimer.T3);
                }
              });
      workers.add(worker);
      worker.start();
    }

    // A reader sampling during the updates sees every counter only grow.
    var monotonic = new AtomicBoolean(true);
    Thread reader =
        new Thread(
            () -> {
              ApciCounters.Snapshot previous = counters.snapshot();
              while (!stop.get()) {
                ApciCounters.Snapshot next = counters.snapshot();
                if (next.iFramesSent() < previous.iFramesSent()
                    || next.bytesSent() < previous.bytesSent()
                    || next.sFramesReceived() < previous.sFramesReceived()
                    || next.t3Expired() < previous.t3Expired()) {
                  monotonic.set(false);
                }
                previous = next;
              }
            });
    reader.start();
    start.countDown();
    for (Thread worker : workers) {
      worker.join();
    }
    stop.set(true);
    reader.join();

    long total = (long) threads * iterations;
    ApciCounters.Snapshot snapshot = counters.snapshot();
    assertEquals(total, snapshot.iFramesSent());
    assertEquals(16 * total, snapshot.bytesSent());
    assertEquals(total, snapshot.sFramesReceived());
    assertEquals(6 * total, snapshot.bytesReceived());
    assertEquals(total, snapshot.t3Expired());
    assertTrue(monotonic.get(), "a counter decreased between snapshots");
  }
}
