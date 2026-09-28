package com.digitalpetri.iec60870.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MonitorPublicationQueueTest {
  @Test
  void holdWaitsAsynchronouslyForAnEarlierPublication() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var harness =
        new Harness(
            4,
            OutboundQueuePolicy.DROP_OLDEST,
            asdu -> {
              entered.countDown();
              await(release);
            })) {
      var publishing = new Thread(() -> harness.queue.submit(asdu(1)));
      publishing.start();
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> snapshot = harness.queue.hold().toCompletableFuture();
        assertFalse(snapshot.isDone());
        harness.queue.submit(asdu(2));
        release.countDown();
        snapshot.get(5, TimeUnit.SECONDS);
        assertTrue(harness.sent.isEmpty());
        harness.queue.resume(() -> {});
        assertEquals(List.of(2), harness.sent);
      } finally {
        release.countDown();
        publishing.join(5000);
        assertFalse(publishing.isAlive());
      }
    }
  }

  @Test
  void nextSnapshotHasAFinitePrefixWhileNewEventsContinueArriving() {
    try (var harness = new Harness(4, OutboundQueuePolicy.DROP_OLDEST, ignored -> {})) {
      assertTrue(harness.queue.hold().toCompletableFuture().isDone());
      harness.queue.submit(asdu(1));
      harness.queue.submit(asdu(2));
      harness.queue.resume(() -> {});
      assertEquals(List.of(1), harness.sent);
      CompletableFuture<Void> nextSnapshot = harness.queue.hold().toCompletableFuture();
      harness.queue.submit(asdu(3));
      harness.writes.get(0).complete(null);
      harness.queue.submit(asdu(4));
      assertFalse(nextSnapshot.isDone());
      harness.writes.get(1).complete(null);
      assertTrue(nextSnapshot.isDone(), "events after the marker must not postpone the snapshot");
      assertEquals(List.of(1, 2), harness.sent);
      harness.queue.resume(() -> {});
      harness.writes.get(2).complete(null);
      assertEquals(List.of(1, 2, 3, 4), harness.sent);
    }
  }

  @Test
  void responseCompletesBeforeAnImmediateReplenishedBacklogDrains() {
    var finished = new CompletableFuture<Void>();
    var holder = new AtomicReference<MonitorPublicationQueue>();
    var sent = new ArrayList<Asdu>();
    var queue =
        new MonitorPublicationQueue(
            ServerConfig.builder().maxOutboundQueue(4).build(),
            ignored -> {},
            asdu -> {
              assertTrue(finished.isDone(), "event drain must not extend interrogation dispatch");
              sent.add(asdu);
              if (sent.size() < 100) holder.get().submit(asdu(sent.size() + 1));
              return CompletableFuture.completedFuture(null);
            },
            Runnable::run,
            error -> fail(error));
    holder.set(queue);
    try {
      queue.hold();
      queue.submit(asdu(1));
      queue.resume(() -> finished.complete(null));
      assertEquals(100, sent.size());
    } finally {
      queue.close();
    }
  }

  @Test
  void repliesFollowTheDeferredBacklogButNotAnInterrogationInProgress() {
    try (var harness = new Harness(4, OutboundQueuePolicy.DROP_OLDEST, ignored -> {})) {
      assertFalse(harness.queue.deferReply(asdu(1)), "an idle queue sends replies directly");
      assertTrue(harness.queue.hold().toCompletableFuture().isDone());
      harness.queue.submit(asdu(2));
      assertFalse(harness.queue.deferReply(asdu(3)), "the interrogation owns the connection");
      harness.queue.resume(() -> {});
      assertTrue(harness.queue.deferReply(asdu(4)));
      harness.queue.submit(asdu(5));
      assertEquals(List.of(2), harness.sent);
      harness.writes.get(0).complete(null);
      harness.writes.get(1).complete(null);
      assertEquals(List.of(2, 4, 5), harness.sent);
      harness.writes.get(2).complete(null);
      assertFalse(harness.queue.deferReply(asdu(6)), "a drained queue sends replies directly");
    }
  }

  @Test
  void eventsNeverEvictDeferredReplies() {
    try (var harness = new Harness(1, OutboundQueuePolicy.DROP_OLDEST, ignored -> {})) {
      harness.queue.hold();
      harness.queue.submit(asdu(1));
      harness.queue.resume(() -> {});
      assertTrue(harness.queue.deferReply(asdu(2)));
      harness.queue.submit(asdu(3));
      harness.queue.submit(asdu(4));
      harness.writes.get(0).complete(null);
      harness.writes.get(1).complete(null);
      assertEquals(List.of(1, 2, 4), harness.sent);
    }
  }

  @Test
  void aReplyAtTheBoundEvictsTheOldestEventOrFailsTheConnection() {
    for (OutboundQueuePolicy policy : OutboundQueuePolicy.values()) {
      try (var harness = new Harness(2, policy, ignored -> {})) {
        harness.queue.hold();
        harness.queue.submit(asdu(1));
        harness.queue.submit(asdu(2));
        harness.queue.resume(() -> {});
        assertTrue(harness.queue.deferReply(asdu(3)));
        assertTrue(harness.queue.deferReply(asdu(4)), "a reply evicts the oldest deferred event");
        assertNull(harness.failure.get());
        assertTrue(harness.queue.deferReply(asdu(5)));
        assertInstanceOf(RejectedExecutionException.class, harness.failure.get());
        harness.writes.get(0).complete(null);
        assertEquals(List.of(1), harness.sent, "a rejected reply closes the queue");
      }
    }
  }

  @Test
  void eventsBetweenRepliesDoNotLiftTheReplyCap() {
    for (OutboundQueuePolicy policy : OutboundQueuePolicy.values()) {
      try (var harness = new Harness(2, policy, ignored -> {})) {
        harness.queue.hold();
        harness.queue.submit(asdu(1));
        harness.queue.resume(() -> {});
        int accepted = 0;
        for (int i = 0; i < 10 && harness.failure.get() == null; i++) {
          harness.queue.submit(asdu(100 + i));
          harness.queue.deferReply(asdu(200 + i));
          if (harness.failure.get() == null) accepted++;
        }
        assertEquals(2, accepted, "replies must stop at the bound");
        assertInstanceOf(RejectedExecutionException.class, harness.failure.get());
      }
    }
  }

  @Test
  void aReplyThatEvictsAnEventWakesABlockedPublisher() throws Exception {
    try (var harness = new Harness(1, OutboundQueuePolicy.BLOCK, ignored -> {})) {
      harness.queue.hold();
      harness.queue.submit(asdu(1));
      harness.queue.resume(() -> {});
      harness.queue.submit(asdu(2));
      var publishing = new Thread(() -> harness.queue.submit(asdu(3)));
      publishing.start();
      try {
        waitForBlocked(publishing);
        assertTrue(harness.queue.deferReply(asdu(4)));
        publishing.join(5000);
        assertFalse(publishing.isAlive(), "the eviction must wake the blocked publisher");
        harness.writes.get(0).complete(null);
        harness.writes.get(1).complete(null);
        assertEquals(List.of(1, 4, 3), harness.sent);
      } finally {
        harness.queue.close();
        publishing.join(5000);
      }
    }
  }

  @Test
  void aReplyWaitsForAnInProgressDirectPublication() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var harness =
        new Harness(
            4,
            OutboundQueuePolicy.BLOCK,
            asdu -> {
              entered.countDown();
              await(release);
            })) {
      var publishing = new Thread(() -> harness.queue.submit(asdu(1)));
      publishing.start();
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(harness.queue.deferReply(asdu(2)));
        assertTrue(harness.sent.isEmpty(), "the reply must follow the older publication");
        release.countDown();
        publishing.join(5000);
        assertFalse(publishing.isAlive());
        assertEquals(List.of(2), harness.sent);
      } finally {
        release.countDown();
        publishing.join(5000);
      }
    }
  }

  @Test
  void deferredOverflowUsesTheConfiguredDropPolicy() {
    for (OutboundQueuePolicy policy :
        List.of(OutboundQueuePolicy.DROP_OLDEST, OutboundQueuePolicy.DROP_NEWEST)) {
      try (var harness = new Harness(2, policy, ignored -> {})) {
        harness.queue.hold();
        harness.queue.submit(asdu(1));
        harness.queue.submit(asdu(2));
        harness.queue.submit(asdu(3));
        harness.queue.resume(() -> {});
        harness.writes.get(0).complete(null);
        assertEquals(
            policy == OutboundQueuePolicy.DROP_OLDEST ? List.of(2, 3) : List.of(1, 2),
            harness.sent);
      }
    }
  }

  @Test
  void closingReleasesABlockedPublisher() throws Exception {
    try (var harness = new Harness(1, OutboundQueuePolicy.BLOCK, ignored -> {})) {
      harness.queue.hold();
      harness.queue.submit(asdu(1));
      var publishing = new Thread(() -> harness.queue.submit(asdu(2)));
      publishing.start();
      try {
        waitForBlocked(publishing);
        harness.queue.close();
        publishing.join(5000);
        assertFalse(publishing.isAlive());
        assertTrue(harness.sent.isEmpty());
      } finally {
        harness.queue.close();
        publishing.join(5000);
      }
    }
  }

  @Test
  void closingFailsABarrierWaitingForAnEarlierPublication() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var harness =
        new Harness(
            1,
            OutboundQueuePolicy.BLOCK,
            ignored -> {
              entered.countDown();
              await(release);
            })) {
      var publishing = new Thread(() -> harness.queue.submit(asdu(1)));
      publishing.start();
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> snapshot = harness.queue.hold().toCompletableFuture();
        harness.queue.close();
        assertTrue(snapshot.isCompletedExceptionally());
      } finally {
        release.countDown();
        publishing.join(5000);
        assertFalse(publishing.isAlive());
      }
    }
  }

  @Test
  void failedDeferredWriteReleasesBlockedPublishers() throws Exception {
    try (var harness = new Harness(1, OutboundQueuePolicy.BLOCK, ignored -> {})) {
      harness.queue.hold();
      harness.queue.submit(asdu(1));
      harness.queue.resume(() -> {});
      harness.queue.submit(asdu(2));
      var publishing = new Thread(() -> harness.queue.submit(asdu(3)));
      publishing.start();
      try {
        waitForBlocked(publishing);
        var failure = new IllegalStateException("write failed");
        harness.writes.get(0).completeExceptionally(failure);
        publishing.join(5000);
        assertFalse(publishing.isAlive());
        assertSame(failure, harness.failure.get());
      } finally {
        harness.queue.close();
        publishing.join(5000);
      }
    }
  }

  @Test
  void thousandsOfDeferredImmediateWritesUseAnIterativeDrain() {
    var sent = new ArrayList<Asdu>();
    var queue =
        new MonitorPublicationQueue(
            ServerConfig.builder().maxOutboundQueue(0).build(),
            sent::add,
            asdu -> {
              sent.add(asdu);
              return CompletableFuture.completedFuture(null);
            },
            Runnable::run,
            error -> fail(error));
    try {
      queue.hold();
      for (int i = 0; i < 5000; i++) queue.submit(asdu(i));
      queue.resume(() -> {});
      assertEquals(5000, sent.size());
    } finally {
      queue.close();
    }
  }

  @Test
  void blockedPublicationHonorsTimeoutAndInterruption() throws Exception {
    var sent = new ArrayList<Asdu>();
    var queue =
        new MonitorPublicationQueue(
            ServerConfig.builder()
                .maxOutboundQueue(1)
                .eventQueuePolicy(OutboundQueuePolicy.BLOCK)
                .outboundBlockTimeout(Duration.ofMillis(10))
                .build(),
            sent::add,
            asdu -> {
              sent.add(asdu);
              return CompletableFuture.completedFuture(null);
            },
            Runnable::run,
            error -> fail(error));
    try {
      queue.hold();
      queue.submit(asdu(1));
      queue.submit(asdu(2));
      queue.resume(() -> {});
      assertEquals(List.of(asdu(1)), sent, "timed-out publication is not retained");
    } finally {
      queue.close();
    }

    try (var harness = new Harness(1, OutboundQueuePolicy.BLOCK, ignored -> {})) {
      harness.queue.hold();
      harness.queue.submit(asdu(1));
      var interrupted = new AtomicReference<Boolean>(false);
      var publishing =
          new Thread(
              () -> {
                harness.queue.submit(asdu(2));
                interrupted.set(Thread.currentThread().isInterrupted());
              });
      publishing.start();
      try {
        waitForBlocked(publishing);
        publishing.interrupt();
        publishing.join(5000);
        assertFalse(publishing.isAlive());
        assertTrue(interrupted.get());
        harness.queue.resume(() -> {});
        assertEquals(List.of(1), harness.sent);
      } finally {
        harness.queue.close();
        publishing.join(5000);
      }
    }
  }

  private static void waitForBlocked(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline)
      Thread.onSpinWait();
    assertEquals(Thread.State.TIMED_WAITING, thread.getState());
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static Asdu asdu(int address) {
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(new ReadCommand(InformationObjectAddress.of(address))));
  }

  private static final class Harness implements AutoCloseable {
    final List<Integer> sent = new ArrayList<>();
    final List<CompletableFuture<Void>> writes = new ArrayList<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final MonitorPublicationQueue queue;

    Harness(int capacity, OutboundQueuePolicy policy, Consumer<Asdu> direct) {
      queue =
          new MonitorPublicationQueue(
              ServerConfig.builder()
                  .maxOutboundQueue(capacity)
                  .eventQueuePolicy(policy)
                  .outboundBlockTimeout(Duration.ofSeconds(30))
                  .build(),
              direct,
              asdu -> {
                sent.add(asdu.objects().get(0).address().value().intValue());
                var write = new CompletableFuture<Void>();
                writes.add(write);
                return write;
              },
              Runnable::run,
              failure::set);
    }

    @Override
    public void close() {
      queue.close();
    }
  }
}
