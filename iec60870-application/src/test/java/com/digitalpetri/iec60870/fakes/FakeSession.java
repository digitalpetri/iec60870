package com.digitalpetri.iec60870.fakes;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.session.Session;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * A neutral in-memory {@link Session} for facade unit tests.
 *
 * <p>This fake implements the {@link Session} SPI directly, with no dependency on any wire-frame
 * type, so the application test scope depends only on {@code iec60870-core}. It models the {@code
 * Asdu}-level behavior the facades rely on:
 *
 * <ul>
 *   <li><b>Data-transfer gating.</b> While data transfer is stopped, {@link #sendAsdu(Asdu)} queues
 *       the ASDU; {@link #startDataTransfer()} flushes the queue and reports the transition through
 *       {@link Session.Events#onDataTransferStateChanged(boolean)}.
 *   <li><b>Bounded outbound queue.</b> When configured with a positive bound, queued ASDUs honor
 *       the configured {@link OutboundQueuePolicy} ({@code DROP_OLDEST}/{@code DROP_NEWEST}; {@code
 *       BLOCK} is enforced by the publisher via {@link #awaitSendCapacity(long)}).
 *   <li><b>Sent-ASDU capture.</b> Every transmitted ASDU is recorded for assertions.
 *   <li><b>Inbound delivery and closure.</b> Tests fire {@link #deliverAsdu(Asdu)} and {@link
 *       #fireClosed(Throwable)} to exercise the facade's {@link Session.Events} callbacks.
 * </ul>
 *
 * <p>The fake is single-thread oriented: facade unit tests drive it from one thread (typically with
 * a same-thread callback executor), so no internal locking is required.
 */
public final class FakeSession implements Session {

  private final Session.Events events;
  private final int maxOutboundQueue;
  private final OutboundQueuePolicy queuePolicy;
  private final boolean serverRole;

  private final List<Asdu> sent = new ArrayList<>();
  private final Deque<Asdu> queued = new ArrayDeque<>();

  private boolean dataTransferStarted;
  private boolean closed;

  private FakeSession(
      Session.Events events,
      int maxOutboundQueue,
      OutboundQueuePolicy queuePolicy,
      boolean serverRole) {
    this.events = events;
    this.maxOutboundQueue = maxOutboundQueue;
    this.queuePolicy = queuePolicy;
    this.serverRole = serverRole;
  }

  /**
   * Creates a CLIENT-role fake session with an unbounded outbound queue.
   *
   * @param events the facade's event sink.
   * @return the fake session.
   */
  public static FakeSession client(Session.Events events) {
    return new FakeSession(events, 0, OutboundQueuePolicy.DROP_OLDEST, false);
  }

  /**
   * Creates a SERVER-role fake session with the given outbound bound and overflow policy.
   *
   * @param events the facade's event sink.
   * @param maxOutboundQueue the outbound queue bound, or {@code 0} for unbounded.
   * @param queuePolicy the overflow policy applied to a bounded queue.
   * @return the fake session.
   */
  public static FakeSession server(
      Session.Events events, int maxOutboundQueue, OutboundQueuePolicy queuePolicy) {
    return new FakeSession(events, maxOutboundQueue, queuePolicy, true);
  }

  // --- Session ---------------------------------------------------------------------------------

  @Override
  public void onConnected() {
    dataTransferStarted = false;
    closed = false;
    queued.clear();
  }

  @Override
  public CompletionStage<Void> startDataTransfer() {
    if (serverRole) {
      throw new IllegalStateException("startDataTransfer is invalid on a SERVER-role session");
    }
    setDataTransferStarted(true);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> stopDataTransfer() {
    if (serverRole) {
      throw new IllegalStateException("stopDataTransfer is invalid on a SERVER-role session");
    }
    setDataTransferStarted(false);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public boolean isDataTransferStarted() {
    return dataTransferStarted;
  }

  @Override
  public void sendAsdu(Asdu asdu) {
    if (closed) {
      return;
    }
    // For a CLIENT-role session data transfer is started on connect, so ASDUs transmit immediately.
    // For a SERVER-role session, ASDUs are withheld until data transfer starts (mirroring STARTDT
    // gating) and the bounded queue enforces the overflow policy.
    if (dataTransferStarted) {
      sent.add(asdu);
      return;
    }
    if (maxOutboundQueue > 0 && queued.size() >= maxOutboundQueue) {
      // DROP_OLDEST evicts the head to make room; every other policy drops the incoming ASDU so the
      // bound is never exceeded. For BLOCK this is a last-resort guard mirroring the real session
      // (the publisher is expected to have awaited capacity first).
      if (queuePolicy == OutboundQueuePolicy.DROP_OLDEST) {
        queued.pollFirst();
        queued.addLast(asdu);
      }
      return;
    }
    queued.addLast(asdu);
  }

  @Override
  public boolean awaitSendCapacity(long timeoutMillis) {
    // The fake never blocks: capacity is reported as available unless the bounded queue is full.
    return maxOutboundQueue <= 0 || queued.size() < maxOutboundQueue;
  }

  @Override
  public int pendingSendCount() {
    return queued.size();
  }

  @Override
  public void close() {
    closed = true;
    queued.clear();
  }

  // --- Test affordances ------------------------------------------------------------------------

  /**
   * Delivers an inbound application ASDU to the facade through {@link Session.Events#onAsdu(Asdu)}.
   *
   * @param asdu the ASDU to deliver.
   */
  public void deliverAsdu(Asdu asdu) {
    events.onAsdu(asdu);
  }

  /**
   * Simulates the peer driving this session into the data-transfer-started state (a SERVER-role
   * session follows the peer's activation rather than initiating it). Flushes any queued ASDUs and
   * reports the transition through {@link Session.Events#onDataTransferStateChanged(boolean)}.
   */
  public void simulateDataTransferStarted() {
    setDataTransferStarted(true);
  }

  /**
   * Fires a self-initiated close to the facade through {@link Session.Events#onClosed(Throwable)},
   * mirroring a protocol-error/timeout close or a transport loss routed through the session.
   *
   * @param cause the close cause, or {@code null} for an orderly close.
   */
  public void fireClosed(@Nullable Throwable cause) {
    if (closed) {
      return;
    }
    closed = true;
    events.onClosed(cause);
  }

  /**
   * Returns the application ASDUs transmitted so far, in order.
   *
   * @return the sent ASDUs.
   */
  public List<Asdu> sentAsdus() {
    return new ArrayList<>(sent);
  }

  private void setDataTransferStarted(boolean started) {
    if (this.dataTransferStarted == started) {
      return;
    }
    this.dataTransferStarted = started;
    events.onDataTransferStateChanged(started);
    if (started) {
      // Flush whatever queued while stopped, honoring the original submission order.
      while (!queued.isEmpty()) {
        sent.add(queued.pollFirst());
      }
    }
  }
}
