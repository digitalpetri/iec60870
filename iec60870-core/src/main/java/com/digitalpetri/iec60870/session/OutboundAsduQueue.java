package com.digitalpetri.iec60870.session;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.asdu.Asdu;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * An outbound ASDU queue for session implementations, with optional bounds and tracked writes.
 *
 * <p>The owning session must serialize queue operations. Write completions may arrive on any
 * thread. Reliable entries cannot be evicted by the overflow policy; offering one to a full queue
 * fails its completion. Call {@link #failAll(Throwable)} on close or connection reset, including
 * for writes already removed from the queue whose transport completion is still pending.
 */
public final class OutboundAsduQueue {
  private final int capacity;
  private final OutboundQueuePolicy policy;
  private final ArrayDeque<Entry> queue = new ArrayDeque<>();
  private final Set<Entry> outstanding = ConcurrentHashMap.newKeySet();

  /**
   * Creates an outbound queue.
   *
   * @param capacity the pending queue bound, or zero for an unbounded queue.
   * @param policy the overflow policy for discardable entries.
   */
  public OutboundAsduQueue(int capacity, OutboundQueuePolicy policy) {
    if (capacity < 0) {
      throw new IllegalArgumentException("capacity must not be negative");
    }
    this.capacity = capacity;
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /**
   * Offers an ASDU, retaining submission order among accepted entries.
   *
   * @param asdu the ASDU to send.
   * @param reliable whether to protect this entry against overflow eviction.
   * @return the entry, whose completion fails immediately when the queue rejects it.
   */
  public Entry offer(Asdu asdu, boolean reliable) {
    var entry = new Entry(Objects.requireNonNull(asdu, "asdu"), reliable);
    if (capacity > 0 && queue.size() >= capacity) {
      Entry evicted =
          !reliable && policy == OutboundQueuePolicy.DROP_OLDEST
              ? removeFirstMatching(candidate -> !candidate.reliable)
              : null;
      if (evicted == null) {
        entry.fail(new RejectedExecutionException("outbound ASDU queue is full"));
        return entry;
      }
      evicted.fail(new RejectedExecutionException("outbound ASDU evicted"));
    }
    outstanding.add(entry);
    entry.completion.whenComplete((ignored, error) -> outstanding.remove(entry));
    queue.addLast(entry);
    return entry;
  }

  /**
   * @return the number of entries waiting to be written.
   */
  public int size() {
    return queue.size();
  }

  /**
   * @return whether no entries are waiting to be written.
   */
  public boolean isEmpty() {
    return queue.isEmpty();
  }

  /**
   * @return the next entry, or {@code null} when empty.
   */
  public @Nullable Entry pollFirst() {
    return queue.pollFirst();
  }

  /**
   * @return the next entry, failing when empty.
   */
  public Entry removeFirst() {
    return queue.removeFirst();
  }

  /**
   * Removes the first matching entry without completing it.
   *
   * @param predicate the eligibility check.
   * @return the first eligible entry, or {@code null} if none matches.
   */
  public @Nullable Entry removeFirstMatching(Predicate<Entry> predicate) {
    var iterator = queue.iterator();
    while (iterator.hasNext()) {
      Entry entry = iterator.next();
      if (predicate.test(entry)) {
        iterator.remove();
        return entry;
      }
    }
    return null;
  }

  /**
   * Checks for an eligible queued entry without removing it.
   *
   * @param predicate the eligibility check.
   * @return whether an entry matches.
   */
  public boolean anyMatch(Predicate<Entry> predicate) {
    return queue.stream().anyMatch(predicate);
  }

  /**
   * Discards queued entries and fails every unfinished write from this queue.
   *
   * @param cause the connection or session failure.
   */
  public void failAll(Throwable cause) {
    queue.clear();
    List<Entry> abandoned = List.copyOf(outstanding);
    outstanding.clear();
    abandoned.forEach(entry -> entry.fail(cause));
  }

  /** One submission and its first transport-write completion. */
  public static final class Entry {
    private final Asdu asdu;
    private final boolean reliable;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private Entry(Asdu asdu, boolean reliable) {
      this.asdu = asdu;
      this.reliable = reliable;
    }

    /**
     * @return the submitted ASDU.
     */
    public Asdu asdu() {
      return asdu;
    }

    /**
     * @return the first-write completion, independent of peer acknowledgement.
     */
    public CompletionStage<Void> completion() {
      return completion;
    }

    /**
     * Relays the transport's completion for the first write.
     *
     * @param write the transport write stage.
     */
    public void completeFrom(CompletionStage<Void> write) {
      write.whenComplete(
          (ignored, error) -> {
            if (error == null) {
              completion.complete(null);
            } else {
              completion.completeExceptionally(error);
            }
          });
    }

    /**
     * Fails an unwritten or unfinished entry.
     *
     * @param cause the reason this submission failed.
     */
    public void fail(Throwable cause) {
      completion.completeExceptionally(cause);
    }
  }
}
