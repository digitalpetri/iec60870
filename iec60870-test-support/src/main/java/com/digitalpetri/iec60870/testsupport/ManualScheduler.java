package com.digitalpetri.iec60870.testsupport;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
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
 * synchronously, in deadline order, on the advancing thread. This lets time-dependent behavior —
 * the APCI {@code t1}/{@code t2}/{@code t3} timers, facade request/command timeouts — be driven and
 * asserted deterministically with no wall-clock sleeps. Combined with a direct (same-thread)
 * callback executor it collapses the scheduler-thread/callback-thread hop to a single deterministic
 * thread, so a timed-out task is run and its effects observed before {@code advance} returns.
 *
 * <p>{@link #execute(Runnable)} runs inline on the caller thread. Only the scheduling members the
 * sessions and facades use are implemented; the remaining {@link ScheduledExecutorService} members
 * throw {@link UnsupportedOperationException}.
 */
public final class ManualScheduler implements ScheduledExecutorService {

  private long nowMillis;
  private long sequence;
  private final PriorityQueue<ScheduledTask<?>> queue = new PriorityQueue<>();

  /** Records, per scheduling delay (ms), the most recently scheduled {@link Runnable}. */
  private final Map<Long, Runnable> lastRunnableByDelay = new HashMap<>();

  /**
   * Advances the virtual clock, running every task whose deadline is at or before the new time.
   *
   * @param amount the amount of time to advance.
   * @param unit the unit of {@code amount}.
   */
  public void advance(long amount, TimeUnit unit) {
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
   * Returns the most recently scheduled {@link Runnable} whose delay equals {@code delayMillis},
   * for tests that need a specific timer task (e.g. t1) rather than the last-scheduled one.
   *
   * @param delayMillis the scheduling delay in milliseconds.
   * @return the most recent runnable scheduled at that delay.
   */
  public Runnable lastRunnableWithDelay(long delayMillis) {
    Runnable runnable = lastRunnableByDelay.get(delayMillis);
    if (runnable == null) {
      throw new IllegalStateException("no runnable scheduled with delay " + delayMillis);
    }
    return runnable;
  }

  /**
   * Returns the number of queued tasks that are still live (neither run nor cancelled).
   *
   * @return the count of pending, non-cancelled tasks.
   */
  public int pendingTaskCount() {
    int count = 0;
    for (ScheduledTask<?> task : queue) {
      if (!task.cancelled && !task.done) {
        count++;
      }
    }
    return count;
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    this.lastRunnableByDelay.put(unit.toMillis(delay), command);
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
  public <T> Future<T> submit(Callable<T> task) {
    throw new UnsupportedOperationException("submit");
  }

  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    throw new UnsupportedOperationException("submit");
  }

  @Override
  public Future<?> submit(Runnable task) {
    throw new UnsupportedOperationException("submit");
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
    throw new UnsupportedOperationException("invokeAll");
  }

  @Override
  public <T> List<Future<T>> invokeAll(
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
    public @Nullable V get() {
      if (cancelled) {
        throw new CancellationException();
      }
      return result;
    }

    @Override
    public @Nullable V get(long timeout, TimeUnit unit) throws TimeoutException {
      if (!done) {
        throw new TimeoutException();
      }
      return get();
    }
  }
}
