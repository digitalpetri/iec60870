package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.session.Session;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The unbalanced FT1.2 outstation (secondary) state machine for IEC 60870-5-101, the serial peer of
 * a 104 SERVER-role {@code ApciSession}.
 *
 * <p>In FT1.2 unbalanced transmission the bus is half-duplex with a single primary station — the
 * master — that owns the line and polls one or more secondary stations. An {@code
 * UnbalancedSlaveEngine} is one such secondary: it <em>never initiates</em> a transfer. Every frame
 * it emits is a response to a primary frame the master sent, so the engine has no primary process,
 * no frame-count-bit (FCB) of its own, and no confirm/repeat timers; the master retransmits when it
 * does not hear a response, and the slave simply replays its last response when it sees the same
 * FCB again.
 *
 * <p>Application data the facade publishes through {@link #sendAsdu(Asdu)} is <em>buffered</em>,
 * not sent: the engine sorts it into two bounded FIFO queues — a class-1 queue for
 * event/spontaneous ASDUs and a class-2 queue for cyclic/periodic ASDUs — and hands one queued ASDU
 * to the master only when the master polls for that data class. The {@linkplain
 * LinkControlField#acd() access-demand (ACD)} bit of each response advertises whether class-1 data
 * is still pending so the master can escalate to a class-1 poll. The {@linkplain
 * LinkControlField#dfc() data-flow-control (DFC)} bit is always {@code 0}: per IEC 60870-5-2 a
 * secondary asserts DFC to report that its <em>receive</em> buffer is full, and this outstation
 * delivers every inbound ASDU synchronously with no receive buffer, so it has no receive-side
 * back-pressure to signal (see {@link #DFC}).
 *
 * <p>The engine is transport-agnostic: outbound frames are handed to the supplied {@link
 * Ft12LinkLayer.Output}, inbound frames are fed in via {@link #onFrame(Ft12Frame)}, and delivered
 * application ASDUs plus lifecycle events are reported through the supplied {@link Session.Events}.
 *
 * <p><b>Lifecycle.</b> Call {@link #onConnected()} once the serial port is open to reset the link
 * state; then feed every inbound frame to {@link #onFrame(Ft12Frame)}. The link becomes available
 * once the master sends its reset-of-remote-link frame, which is reported through {@link
 * Session.Events#onDataTransferStateChanged(boolean)}. As a SERVER-role station the slave never
 * drives {@link #startDataTransfer()}/{@link #stopDataTransfer()}; both throw {@link
 * IllegalStateException}. Call {@link #close()} when the transport goes away; it is idempotent.
 *
 * <p><b>Threading.</b> All mutable state is guarded by a single internal lock, so a caller may
 * invoke any method from any thread. The {@link Ft12LinkLayer.Output} and {@link Session.Events}
 * callbacks are always invoked while the lock is held; they must not call back into the engine and
 * should not block. The engine is purely reactive and arms no timers, so it self-closes for no
 * reason — session teardown is driven externally (by the binding) when the transport is lost.
 */
final class UnbalancedSlaveEngine implements Ft12Engine {

  private static final Logger LOGGER = LoggerFactory.getLogger(UnbalancedSlaveEngine.class);

  // Primary (PRM=1) function codes the master sends; the slave answers them with its secondary
  // process.
  private static final int FC_RESET_REMOTE_LINK = 0;
  private static final int FC_SEND_CONFIRM_USER_DATA = 3;
  private static final int FC_SEND_NO_REPLY_USER_DATA = 4;
  private static final int FC_REQUEST_STATUS_OF_LINK = 9;
  private static final int FC_REQUEST_USER_DATA_CLASS_1 = 10;
  private static final int FC_REQUEST_USER_DATA_CLASS_2 = 11;

  // Secondary (PRM=0) function codes the slave emits in response.
  private static final int FC_ACK = 0;
  private static final int FC_RESPOND_USER_DATA = 8;
  private static final int FC_RESPOND_DATA_NOT_AVAILABLE = 9;
  private static final int FC_STATUS_OF_LINK = 11;

  /**
   * The data-flow-control (DFC) bit this secondary advertises in every response, pinned to {@code
   * 0} (never back-pressured).
   *
   * <p>Per IEC 60870-5-2 a secondary sets DFC=1 to tell the primary that its own <em>receive</em>
   * buffer is full and that the primary must stop sending it user data. This outstation delivers
   * every inbound ASDU synchronously through {@link Session.Events#onAsdu(Asdu)} and holds no
   * receive buffer, so it has no genuine receive-side back-pressure to report and never asserts
   * DFC. The bounded class-1 / class-2 queues are <em>transmit</em>-side buffers; their saturation
   * is a send-side concern surfaced to publishers through {@link #awaitSendCapacity(long)} and the
   * {@link OutboundQueuePolicy}. Driving DFC from a full transmit queue would wrongly tell a
   * healthy master to withhold commands from a slave that merely has a lot to report.
   */
  private static final boolean DFC = false;

  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Signalled whenever a queued ASDU is dequeued or the queues are cleared, so a parked publisher
   * can re-check space.
   */
  private final Condition queueDrained = lock.newCondition();

  private final Ft12LinkLayer.Output output;
  private final Session.Events events;

  private final boolean useSingleCharAck;
  private final int linkAddress;

  /**
   * The all-secondaries broadcast address ({@code 255} for a one-octet, {@code 65535} for a
   * two-octet link address). A frame addressed here is processed but never answered.
   */
  private final int broadcastAddress;

  // Outbound queue bound and overflow policy (0 == unbounded), applied to each class queue.
  private final int maxOutboundQueue;
  private final OutboundQueuePolicy queuePolicy;

  private boolean closed;

  /** True once the master has reset this secondary with a reset-of-remote-link frame. */
  private boolean linkReset;

  /** Whether the link is available for user data; equals {@link #isDataTransferStarted()}. */
  private boolean linkAvailable;

  /**
   * The FCB carried by the last FCV=1 primary frame this slave accepted; a frame whose FCB equals
   * this value is a retransmission and replays {@link #lastResponse}. Resets to {@code false} on a
   * link reset, so the master's first post-reset FCV=1 frame (FCB=1) is always treated as new.
   */
  private boolean expectedFcb;

  /**
   * The last secondary frame sent in answer to an FCV=1 primary frame, replayed on an unchanged
   * FCB.
   */
  private @Nullable Ft12Frame lastResponse;

  // Event/spontaneous ASDUs (class 1) and cyclic/periodic ASDUs (class 2), each bounded by
  // maxOutboundQueue. A class-1/2 poll dequeues one ASDU from the matching queue.
  private final ArrayDeque<Asdu> class1Queue = new ArrayDeque<>();
  private final ArrayDeque<Asdu> class2Queue = new ArrayDeque<>();

  /**
   * Creates an unbalanced outstation engine with a bounded outbound queue per data class and an
   * overflow policy.
   *
   * @param settings the FT1.2 link parameters; its {@linkplain LinkSettings#mode() mode} must be
   *     {@link LinkMode#UNBALANCED}.
   * @param scheduler the scheduler the dispatcher injects into every engine; unused here because
   *     the purely-reactive outstation arms no timers, but validated for a uniform contract.
   * @param output the sink for outbound frames.
   * @param events the callbacks for delivered ASDUs and lifecycle transitions.
   * @param maxOutboundQueue the maximum number of buffered ASDUs held in each class queue, or
   *     {@code 0} for an unbounded queue.
   * @param queuePolicy the action taken when a bounded class queue overflows.
   * @throws IllegalArgumentException if {@code settings} is not in unbalanced mode, or if {@code
   *     maxOutboundQueue} is negative.
   */
  UnbalancedSlaveEngine(
      LinkSettings settings,
      ScheduledExecutorService scheduler,
      Ft12LinkLayer.Output output,
      Session.Events events,
      int maxOutboundQueue,
      OutboundQueuePolicy queuePolicy) {

    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(scheduler, "scheduler");
    this.output = Objects.requireNonNull(output, "output");
    this.events = Objects.requireNonNull(events, "events");
    if (settings.mode() != LinkMode.UNBALANCED) {
      throw new IllegalArgumentException(
          "UnbalancedSlaveEngine supports UNBALANCED mode only, got: " + settings.mode());
    }
    if (maxOutboundQueue < 0) {
      throw new IllegalArgumentException("maxOutboundQueue must be >= 0: " + maxOutboundQueue);
    }
    this.maxOutboundQueue = maxOutboundQueue;
    this.queuePolicy = Objects.requireNonNull(queuePolicy, "queuePolicy");

    this.useSingleCharAck = settings.useSingleCharAck();
    this.linkAddress = settings.linkAddress();
    this.broadcastAddress = settings.broadcastAddress();

    // Leave the engine in a valid reset state so it is usable even before onConnected().
    resetState();
  }

  /**
   * Resets the engine for a freshly established transport connection.
   *
   * <p>The link returns to its unavailable, not-yet-reset state, the expected FCB and both class
   * queues are cleared, and any cached response is dropped. The data-transfer state change is not
   * reported from here; it is reported when the master resets the link.
   */
  @Override
  public void onConnected() {
    lock.lock();
    try {
      closed = false;
      resetState();
      // Clearing the queues frees capacity; wake any publisher parked on the BLOCK policy.
      queueDrained.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Rejects a start-data-transfer request: an unbalanced outstation is a SERVER-role station and
   * never initiates the link-reset bring-up; it follows the master's reset instead.
   *
   * @return never returns normally.
   * @throws IllegalStateException always, because the outstation is a SERVER-role station.
   */
  @Override
  public CompletionStage<Void> startDataTransfer() {
    throw new IllegalStateException("startDataTransfer is only valid for a CLIENT session");
  }

  /**
   * Rejects a stop-data-transfer request: an unbalanced outstation is a SERVER-role station and
   * never drives the data-transfer lifecycle.
   *
   * @return never returns normally.
   * @throws IllegalStateException always, because the outstation is a SERVER-role station.
   */
  @Override
  public CompletionStage<Void> stopDataTransfer() {
    throw new IllegalStateException("stopDataTransfer is only valid for a CLIENT session");
  }

  /**
   * Reports whether the master has reset the link and it is available for user-data transfer.
   *
   * @return {@code true} if the link is available.
   */
  @Override
  public boolean isDataTransferStarted() {
    lock.lock();
    try {
      return linkAvailable;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Buffers an application ASDU for a later poll; an unbalanced outstation never sends on its own.
   *
   * <p>The ASDU is classified by its {@linkplain Asdu#cause() cause of transmission} and appended
   * to the matching class queue: a {@link Cause#SPONTANEOUS spontaneous} cause routes to the
   * class-1 (event) queue and every other cause routes to the class-2 (cyclic) queue. This
   * single-cause heuristic is frozen because the facade has one publish path, where the lib60870
   * model leaves the class choice to the application via separate class-1/class-2 enqueue calls.
   * The ASDU is transmitted only when the master polls the matching data class.
   *
   * @param asdu the application ASDU to buffer.
   */
  @Override
  public void sendAsdu(Asdu asdu) {
    Objects.requireNonNull(asdu, "asdu");
    lock.lock();
    try {
      if (closed) {
        return;
      }
      // Frozen class-assignment heuristic: spontaneous events are class 1, everything else class 2.
      ArrayDeque<Asdu> queue = asdu.cause() == Cause.SPONTANEOUS ? class1Queue : class2Queue;
      enqueueBounded(queue, asdu);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Awaits free capacity in the outbound queues, off the engine's hot path, for the {@link
   * OutboundQueuePolicy#BLOCK} policy.
   *
   * <p>Parks on the drain signal until the combined depth of both class queues falls below the
   * configured bound, the session closes, or the deadline elapses.
   *
   * @param timeoutMillis the maximum time to wait, in milliseconds.
   * @return {@code true} if capacity is available (or the queue is unbounded / session closed),
   *     {@code false} if the wait timed out with the queues still full.
   * @throws InterruptedException if the current thread is interrupted while waiting.
   */
  @Override
  public boolean awaitSendCapacity(long timeoutMillis) throws InterruptedException {
    if (maxOutboundQueue <= 0) {
      return true;
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    lock.lock();
    try {
      while (!closed && class1Queue.size() + class2Queue.size() >= maxOutboundQueue) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return false;
        }
        if (!queueDrained.await(remaining, TimeUnit.NANOSECONDS)) {
          return false;
        }
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the combined number of ASDUs currently buffered across both class queues (not yet
   * handed to the master in a poll response).
   *
   * @return the pending buffered ASDU count.
   */
  @Override
  public int pendingSendCount() {
    lock.lock();
    try {
      return class1Queue.size() + class2Queue.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Closes the engine.
   *
   * <p>Marks the session closed and wakes any publisher parked on capacity. This method is
   * idempotent and does not invoke {@link Session.Events#onClosed(Throwable)} — the outstation
   * never self-closes, so an external close is always an orderly teardown.
   */
  @Override
  public void close() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      queueDrained.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Handles a single inbound FT1.2 frame from the master.
   *
   * <p>The master is always the primary station, so every inbound fixed- or variable-length frame
   * is routed through this slave's secondary process. A single-character {@code 0xE5} frame, or a
   * frame that carries a secondary control field, is not part of the unbalanced secondary process
   * and is ignored.
   *
   * <p><b>Link-address filtering.</b> On a real multi-drop RS-485 bus every secondary's UART
   * receives every master frame, so the slave must match the frame's link address against its own
   * before acting: a frame addressed to a <em>different</em> specific secondary is ignored entirely
   * (no response, no delivery, no state change), so this station never answers a poll, reset, or
   * command meant for a peer. A frame addressed to the all-secondaries {@linkplain
   * LinkSettings#broadcastAddress() broadcast address} is processed but never answered, because a
   * broadcast service is unconfirmed and a reply would collide with every other secondary on the
   * bus.
   *
   * @param frame the inbound frame.
   */
  @Override
  public void onFrame(Ft12Frame frame) {
    Objects.requireNonNull(frame, "frame");
    lock.lock();
    try {
      if (closed) {
        return;
      }
      if (frame instanceof Ft12Frame.FixedLength fixed) {
        dispatchByAddress(fixed.linkAddress(), fixed.control(), null);
      } else if (frame instanceof Ft12Frame.Variable variable) {
        dispatchByAddress(variable.linkAddress(), variable.control(), variable.asdu());
      } else {
        LOGGER.debug("ignoring unexpected single-character frame on an unbalanced slave link");
      }
    } finally {
      lock.unlock();
    }
  }

  // --- Inbound dispatch (lock held) -----------------------------------------------------------

  /**
   * Routes an inbound primary frame by its link address: own-addressed frames run the full
   * secondary process, broadcast-addressed frames are delivered without a reply, and frames for any
   * other secondary are dropped. The own-address check is made first so a station whose own address
   * happens to equal the broadcast address still answers its own polls.
   *
   * @param frameAddress the inbound frame's link address.
   * @param control the inbound primary control field.
   * @param asdu the carried ASDU, or {@code null} if absent.
   */
  private void dispatchByAddress(int frameAddress, LinkControlField control, @Nullable Asdu asdu) {
    if (frameAddress == linkAddress) {
      handlePrimary(control, asdu);
    } else if (frameAddress == broadcastAddress) {
      handleBroadcast(control, asdu);
    } else {
      LOGGER.debug(
          "ignoring frame addressed to link address {} on unbalanced slave {}",
          frameAddress,
          linkAddress);
    }
  }

  /**
   * Handles a frame addressed to the all-secondaries broadcast address. A broadcast service is
   * unconfirmed, so the carried user data is delivered but no response is ever sent (a reply would
   * collide with every other secondary on the bus). Only send/no-reply user data (FC4) is a valid
   * broadcast service; any confirmed or control function code addressed to the broadcast address is
   * meaningless and is ignored.
   *
   * @param control the inbound primary control field.
   * @param asdu the carried ASDU, or {@code null} if absent.
   */
  private void handleBroadcast(LinkControlField control, @Nullable Asdu asdu) {
    if (!control.prm()) {
      LOGGER.debug(
          "ignoring unexpected secondary frame addressed to the broadcast address: FC{}",
          control.functionCode());
      return;
    }
    int fc = control.functionCode();
    if (fc == FC_SEND_NO_REPLY_USER_DATA) {
      handleSendNoReply(asdu);
    } else {
      LOGGER.debug(
          "ignoring non-broadcast-service function code on an unbalanced slave broadcast: FC{}",
          fc);
    }
  }

  private void handlePrimary(LinkControlField control, @Nullable Asdu asdu) {
    if (!control.prm()) {
      // The master is always the primary station; a secondary frame on the bus is unexpected.
      LOGGER.debug(
          "ignoring unexpected secondary frame received by an unbalanced slave: FC{}",
          control.functionCode());
      return;
    }
    int fc = control.functionCode();
    switch (fc) {
      case FC_REQUEST_STATUS_OF_LINK -> respondStatusOfLink();
      case FC_RESET_REMOTE_LINK -> handleReset();
      case FC_REQUEST_USER_DATA_CLASS_2 -> handleRequestClass2(control);
      case FC_REQUEST_USER_DATA_CLASS_1 -> handleRequestClass1(control);
      case FC_SEND_CONFIRM_USER_DATA -> handleSendConfirm(control, asdu);
      case FC_SEND_NO_REPLY_USER_DATA -> handleSendNoReply(asdu);
      default ->
          LOGGER.debug("ignoring unsupported primary function code on unbalanced slave: FC{}", fc);
    }
  }

  /**
   * Answers a request-status-of-link (FC9) with a status-of-link (FC11) frame. The frame carries
   * the access-demand bit (so the master can learn of pending class-1 data) and the
   * data-flow-control bit. FC9 has FCV=0, so it is not FCB-checked and the response is not cached
   * for replay.
   */
  private void respondStatusOfLink() {
    boolean acd = !class1Queue.isEmpty();
    output.send(secondaryFixed(FC_STATUS_OF_LINK, acd));
  }

  /**
   * Handles a reset-of-remote-link (FC0): bring the link up, clear the FCB and cached response, and
   * acknowledge. FC0 has FCV=0, so it is not FCB-checked; the class queues are preserved (only
   * {@link #onConnected()} clears them).
   */
  private void handleReset() {
    linkReset = true;
    expectedFcb = false;
    lastResponse = null;
    if (!linkAvailable) {
      linkAvailable = true;
      events.onDataTransferStateChanged(true);
    }
    // The Events callback runs under the lock and must not re-enter; re-check closed defensively
    // before sending.
    if (closed) {
      return;
    }
    boolean acd = !class1Queue.isEmpty();
    output.send(e5OrFixed(FC_ACK, acd));
  }

  /**
   * Handles a request-class-2-data (FC11, FCV=1): dequeue one class-2 ASDU as a respond-user-data
   * (FC8) frame, falling back to a class-1 ASDU when the class-2 queue is empty (a slave with no
   * class-2 data may answer a class-2 request with class-1 data), and to a data-not-available (FC9)
   * frame when both queues are empty.
   *
   * @param control the inbound primary control field, supplying the FCB.
   */
  private void handleRequestClass2(LinkControlField control) {
    boolean fcb = control.fcb();
    if (isRetransmission(fcb)) {
      replayLastResponse();
      return;
    }
    expectedFcb = fcb;
    Asdu data = class2Queue.pollFirst();
    if (data == null) {
      data = class1Queue.pollFirst();
    }
    respondToDataRequest(data);
  }

  /**
   * Handles a request-class-1-data (FC10, FCV=1): dequeue one class-1 ASDU as a respond-user-data
   * (FC8) frame, or answer data-not-available (FC9) when the class-1 queue is empty.
   *
   * @param control the inbound primary control field, supplying the FCB.
   */
  private void handleRequestClass1(LinkControlField control) {
    boolean fcb = control.fcb();
    if (isRetransmission(fcb)) {
      replayLastResponse();
      return;
    }
    expectedFcb = fcb;
    respondToDataRequest(class1Queue.pollFirst());
  }

  /**
   * Builds, caches, and sends the response to a class-1/class-2 data request after the requested
   * ASDU (if any) has been dequeued: respond-user-data (FC8) carrying the ASDU, or
   * data-not-available (FC9 / E5) when there was nothing to send. The access-demand bit reflects
   * the class-1 queue <em>after</em> the dequeue; the data-flow-control bit is always {@code 0}
   * (see {@link #DFC}).
   *
   * @param data the dequeued ASDU, or {@code null} when no data was available.
   */
  private void respondToDataRequest(@Nullable Asdu data) {
    Ft12Frame response;
    if (data != null) {
      // A buffered ASDU was removed; wake any publisher parked on capacity.
      queueDrained.signalAll();
      boolean acd = !class1Queue.isEmpty();
      response =
          new Ft12Frame.Variable(
              LinkControlField.secondary(false, acd, DFC, FC_RESPOND_USER_DATA), linkAddress, data);
    } else {
      boolean acd = !class1Queue.isEmpty();
      response = e5OrFixed(FC_RESPOND_DATA_NOT_AVAILABLE, acd);
    }
    lastResponse = response;
    output.send(response);
  }

  /**
   * Handles a send/confirm user-data (FC3, FCV=1) command. User data that arrives before the master
   * has reset the link bypasses the link-reset handshake that gates data transfer; a not-reset
   * secondary makes no reply (IEC 60870-5-101 6.2.1.1, Figure 6 not-reset self-loop), so it is
   * dropped and never delivered. Once the link is reset, a changed FCB delivers the carried ASDU
   * and acknowledges, while an unchanged FCB replays the cached acknowledgement without
   * re-delivering.
   *
   * @param control the inbound primary control field, supplying the FCB.
   * @param asdu the carried command ASDU, or {@code null} if absent.
   */
  private void handleSendConfirm(LinkControlField control, @Nullable Asdu asdu) {
    if (!linkReset) {
      // User data before a link reset bypasses the handshake that gates data transfer.
      // The expected FCB sequence is not yet established, so the frame cannot be told
      // apart as a fresh send versus a replay. A not-reset secondary makes no reply
      // (the Figure 6 not-reset self-loop, and the sibling FC4 path), so deliver
      // nothing and stay silent; the master resets the link before retrying.
      LOGGER.debug("received user data before a link reset on an unbalanced slave; dropping");
      return;
    }
    boolean fcb = control.fcb();
    if (isRetransmission(fcb)) {
      replayLastResponse();
      return;
    }
    expectedFcb = fcb;
    boolean acd = !class1Queue.isEmpty();
    Ft12Frame ack = e5OrFixed(FC_ACK, acd);
    lastResponse = ack;
    if (asdu != null) {
      events.onAsdu(asdu);
    }
    // Delivery may have synchronously closed the session (an application send failure); if so, do
    // not acknowledge on a closed session.
    if (closed) {
      return;
    }
    output.send(ack);
  }

  /**
   * Handles a send/no-reply user-data (FC4) broadcast: deliver the carried ASDU and send no
   * response. FC4 has FCV=0 and addresses all secondaries, so it is neither FCB-checked nor
   * acknowledged.
   *
   * @param asdu the carried ASDU, or {@code null} if absent.
   */
  private void handleSendNoReply(@Nullable Asdu asdu) {
    if (asdu != null) {
      events.onAsdu(asdu);
    }
  }

  // --- Outbound helpers (lock held) -----------------------------------------------------------

  /**
   * Returns the cached single-character {@code 0xE5} acknowledgement when single-character
   * acknowledgements are enabled and the access-demand bit is clear, otherwise a full fixed-length
   * secondary frame so the access-demand bit can be carried. The data-flow-control bit is always
   * {@code 0} (see {@link #DFC}), so it never forces the full-frame form.
   *
   * @param functionCode the secondary function code of the full-frame form.
   * @param acd the access-demand bit to carry on the full-frame form.
   * @return the acknowledgement frame.
   */
  private Ft12Frame e5OrFixed(int functionCode, boolean acd) {
    if (useSingleCharAck && !acd) {
      return new Ft12Frame.SingleChar();
    }
    return secondaryFixed(functionCode, acd);
  }

  private Ft12Frame secondaryFixed(int functionCode, boolean acd) {
    return new Ft12Frame.FixedLength(
        LinkControlField.secondary(false, acd, DFC, functionCode), linkAddress);
  }

  private boolean isRetransmission(boolean fcb) {
    return lastResponse != null && fcb == expectedFcb;
  }

  private void replayLastResponse() {
    Ft12Frame cached = lastResponse;
    if (cached != null) {
      output.send(cached);
    }
  }

  private void enqueueBounded(ArrayDeque<Asdu> queue, Asdu asdu) {
    if (maxOutboundQueue > 0 && queue.size() >= maxOutboundQueue) {
      switch (queuePolicy) {
        case DROP_OLDEST -> {
          queue.pollFirst();
          queue.addLast(asdu);
        }
        case DROP_NEWEST, BLOCK -> {
          // Drop the newly offered ASDU; keep the already buffered history. BLOCK is never honored
          // by parking here (that would block under the lock); the publisher is expected to have
          // awaited capacity via awaitSendCapacity(...) first, so a full BLOCK queue drops the
          // newest as a last-resort guard that keeps the bound.
        }
      }
    } else {
      queue.addLast(asdu);
    }
  }

  private void resetState() {
    linkReset = false;
    linkAvailable = false;
    expectedFcb = false;
    lastResponse = null;
    class1Queue.clear();
    class2Queue.clear();
  }
}
