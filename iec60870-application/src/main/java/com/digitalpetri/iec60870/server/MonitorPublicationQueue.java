package com.digitalpetri.iec60870.server;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.asdu.Asdu;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Defers monitor publications while an interrogation snapshot is being sent. */
final class MonitorPublicationQueue {
  private final int capacity;
  private final OutboundQueuePolicy policy;
  private final long timeoutNanos;
  private final Consumer<Asdu> directSend;
  private final Function<Asdu, CompletionStage<Void>> deferredSend;
  private final Executor executor;
  private final Consumer<Throwable> onFailure;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition capacityAvailable = lock.newCondition();
  private final Deque<Pending> queue = new ArrayDeque<>();
  private final AtomicInteger work = new AtomicInteger();
  private int eventCount;
  private int directSubmissions;
  private boolean paused;
  private boolean draining;
  private boolean closed;
  // Only the iterative drain accesses the current write.
  private @Nullable CompletableFuture<Void> write;

  MonitorPublicationQueue(
      ServerConfig config,
      Consumer<Asdu> directSend,
      Function<Asdu, CompletionStage<Void>> deferredSend,
      Executor executor,
      Consumer<Throwable> onFailure) {
    capacity = config.maxOutboundQueue();
    policy = config.eventQueuePolicy();
    timeoutNanos = config.outboundBlockTimeout().toNanos();
    this.directSend = directSend;
    this.deferredSend = deferredSend;
    this.executor = executor;
    this.onFailure = onFailure;
  }

  void submit(Asdu asdu) {
    lock.lock();
    try {
      long remaining = timeoutNanos;
      while (!closed && capacity > 0 && eventCount >= capacity) {
        if (policy == OutboundQueuePolicy.DROP_NEWEST) {
          return;
        }
        if (policy == OutboundQueuePolicy.DROP_OLDEST) {
          var iterator = queue.iterator();
          while (iterator.hasNext()) {
            if (iterator.next() instanceof Event) {
              iterator.remove();
              eventCount--;
              break;
            }
          }
          break;
        }
        if (remaining <= 0) {
          return;
        }
        try {
          remaining = capacityAvailable.awaitNanos(remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      if (closed) {
        return;
      }
      if (paused || draining || !queue.isEmpty()) {
        queue.addLast(new Event(asdu));
        eventCount++;
        return;
      }
      directSubmissions++;
    } finally {
      lock.unlock();
    }
    try {
      directSend.accept(asdu);
    } finally {
      lock.lock();
      try {
        directSubmissions--;
      } finally {
        lock.unlock();
      }
      drain();
    }
  }

  CompletionStage<Void> hold() {
    var ready = new CompletableFuture<Void>();
    lock.lock();
    try {
      if (closed) {
        return CompletableFuture.failedFuture(new ConnectionClosedException("connection closed"));
      }
      // This marker follows only a finite prefix. New events cannot postpone the snapshot.
      queue.addLast(new Barrier(ready));
    } finally {
      lock.unlock();
    }
    drain();
    return ready;
  }

  void resume(Runnable resumed) {
    lock.lock();
    try {
      paused = false;
    } finally {
      lock.unlock();
    }
    // Finish the interrogation dispatch before draining: new events cannot prolong its result.
    resumed.run();
    drain();
  }

  void close() {
    List<CompletableFuture<Void>> barriers = new ArrayList<>();
    lock.lock();
    try {
      closed = true;
      for (Pending pending : queue) {
        if (pending instanceof Barrier barrier) {
          barriers.add(barrier.ready());
        }
      }
      queue.clear();
      eventCount = 0;
      capacityAvailable.signalAll();
    } finally {
      lock.unlock();
    }
    barriers.forEach(
        future -> future.completeExceptionally(new ConnectionClosedException("connection closed")));
  }

  private void drain() {
    // Write completions and direct executors may re-enter without adding a stack frame per event.
    if (work.getAndIncrement() != 0) {
      return;
    }
    do {
      try {
        while (true) {
          if (write != null) {
            if (!write.isDone()) {
              break;
            }
            write.join();
            write = null;
          }
          Pending pending;
          lock.lock();
          try {
            if (closed || paused || directSubmissions > 0) {
              break;
            }
            pending = queue.pollFirst();
            if (pending == null) {
              draining = false;
              break;
            }
            if (pending instanceof Barrier) {
              paused = true;
              draining = false;
            } else {
              draining = true;
              eventCount--;
              capacityAvailable.signalAll();
            }
          } finally {
            lock.unlock();
          }
          if (pending instanceof Barrier barrier) {
            barrier.ready().complete(null);
          } else if (pending instanceof Event event) {
            write = deferredSend.apply(event.asdu()).toCompletableFuture();
            if (!write.isDone()) {
              write.whenComplete((ignored, error) -> executor.execute(this::drain));
              break;
            }
          }
        }
      } catch (RuntimeException error) {
        write = null;
        close();
        onFailure.accept(error);
      }
    } while (work.decrementAndGet() != 0);
  }

  private sealed interface Pending permits Event, Barrier {}

  private record Event(Asdu asdu) implements Pending {}

  private record Barrier(CompletableFuture<Void> ready) implements Pending {}
}
