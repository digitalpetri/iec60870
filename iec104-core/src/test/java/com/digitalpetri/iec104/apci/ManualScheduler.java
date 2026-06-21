package com.digitalpetri.iec104.apci;

import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * A deterministic, single-threaded {@link ScheduledExecutorService} backed by a virtual clock.
 *
 * <p>Tasks are not run on a background thread; time only advances when {@link #advance(long,
 * TimeUnit)} is called, and any tasks whose deadline falls within the advanced interval are run
 * synchronously, in deadline order, on the advancing thread. This lets APCI timer behavior be
 * driven and asserted deterministically.
 *
 * <p>Only the scheduling methods used by {@code ApciSession} are implemented; the remaining {@link
 * ScheduledExecutorService} members throw {@link UnsupportedOperationException}.
 */
final class ManualScheduler implements ScheduledExecutorService {

  private long nowMillis;
  private long sequence;
  private final PriorityQueue<ScheduledTask<?>> queue = new PriorityQueue<>();

  /**
   * Advances the virtual clock, running every task whose deadline is at or before the new time.
   *
   * @param amount the amount of time to advance.
   * @param unit the unit of {@code amount}.
   */
  void advance(long amount, TimeUnit unit) {
    long target = nowMillis + unit.toMillis(amount);
    while (true) {
      ScheduledTask<?> next = queue.peek();
      if (next == null || next.deadlineMillis > target) {
        break;
      }
      queue.poll();
      nowMillis = next.deadlineMillis;
      if (!next.cancelled) {
        next.run();
      }
    }
    nowMillis = target;
  }

  /**
   * Returns the number of tasks currently scheduled and not yet run or cancelled.
   *
   * @return the pending task count.
   */
  int pendingTaskCount() {
    int count = 0;
    for (ScheduledTask<?> task : queue) {
      if (!task.cancelled) {
        count++;
      }
    }
    return count;
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    ScheduledTask<@Nullable Void> task =
        new ScheduledTask<@Nullable Void>(
            () -> {
              command.run();
              return null;
            },
            nowMillis + unit.toMillis(delay),
            sequence++);
    queue.add(task);
    return task;
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    ScheduledTask<V> task =
        new ScheduledTask<>(callable, nowMillis + unit.toMillis(delay), sequence++);
    queue.add(task);
    return task;
  }

  @Override
  public void execute(Runnable command) {
    command.run();
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable command, long initialDelay, long period, TimeUnit unit) {
    throw new UnsupportedOperationException("scheduleAtFixedRate");
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable command, long initialDelay, long delay, TimeUnit unit) {
    throw new UnsupportedOperationException("scheduleWithFixedDelay");
  }

  @Override
  public void shutdown() {
    queue.clear();
  }

  @Override
  public List<Runnable> shutdownNow() {
    queue.clear();
    return List.of();
  }

  @Override
  public boolean isShutdown() {
    return false;
  }

  @Override
  public boolean isTerminated() {
    return false;
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    return true;
  }

  @Override
  public <T> java.util.concurrent.Future<T> submit(Callable<T> task) {
    throw new UnsupportedOperationException("submit");
  }

  @Override
  public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
    throw new UnsupportedOperationException("submit");
  }

  @Override
  public java.util.concurrent.Future<?> submit(Runnable task) {
    throw new UnsupportedOperationException("submit");
  }

  @Override
  public <T> List<java.util.concurrent.Future<T>> invokeAll(
      Collection<? extends Callable<T>> tasks) {
    throw new UnsupportedOperationException("invokeAll");
  }

  @Override
  public <T> List<java.util.concurrent.Future<T>> invokeAll(
      Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
    throw new UnsupportedOperationException("invokeAll");
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
    throw new UnsupportedOperationException("invokeAny");
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
    throw new UnsupportedOperationException("invokeAny");
  }

  /** A scheduled task with a virtual deadline and a tie-breaking insertion order. */
  private static final class ScheduledTask<V extends @Nullable Object>
      implements ScheduledFuture<V>, Comparable<Delayed> {

    private final Callable<V> callable;
    private final long deadlineMillis;
    private final long order;
    private volatile boolean cancelled;
    private volatile boolean done;
    private @Nullable V result;

    ScheduledTask(Callable<V> callable, long deadlineMillis, long order) {
      this.callable = callable;
      this.deadlineMillis = deadlineMillis;
      this.order = order;
    }

    void run() {
      try {
        result = callable.call();
        done = true;
      } catch (Exception e) {
        done = true;
        throw new RuntimeException(e);
      }
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(deadlineMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
      if (other instanceof ScheduledTask<?> task) {
        int byDeadline = Long.compare(deadlineMillis, task.deadlineMillis);
        return byDeadline != 0 ? byDeadline : Long.compare(order, task.order);
      }
      return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      if (done || cancelled) {
        return false;
      }
      cancelled = true;
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return done || cancelled;
    }

    @Override
    public @Nullable V get() throws ExecutionException {
      if (cancelled) {
        throw new java.util.concurrent.CancellationException();
      }
      return result;
    }

    @Override
    public @Nullable V get(long timeout, TimeUnit unit)
        throws ExecutionException, TimeoutException {
      if (!done) {
        throw new TimeoutException();
      }
      return get();
    }
  }
}
