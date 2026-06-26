package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.session.Session;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The balanced FT1.2 link state machine for IEC 60870-5-101, the serial peer of the 104 {@code
 * ApciSession}.
 *
 * <p>A {@code Ft12LinkLayer} owns the stop-and-wait flow control for one point-to-point serial
 * link: the frame count bit (FCB) sequence of its primary process, the secondary process that
 * acknowledges frames received from the peer, the link-reset bring-up handshake, and the
 * confirm/repeat/link-state timers. It is transport-agnostic — outbound frames are handed to the
 * supplied {@link Output}, inbound frames are fed in via {@link #onFrame(Ft12Frame)}, and delivered
 * application ASDUs plus lifecycle events are reported through the supplied {@link Session.Events}.
 * The link layer never touches a serial port or a {@code ByteBuf} directly.
 *
 * <p>In FT1.2 balanced transmission both stations are <em>combined</em>: each runs a primary
 * process (frames it initiates, with its own FCB) and a secondary process (acknowledging frames it
 * receives, tracking the peer's FCB) at the same time, regardless of {@link Role}. The role selects
 * only the small asymmetries: the DIR bit on every outgoing frame, which station initiates the
 * link-reset bring-up, and which station may drive {@link #startDataTransfer()}/{@link
 * #stopDataTransfer()}.
 *
 * <p>The link layer is constructed for {@link LinkMode#BALANCED balanced} mode only; the unbalanced
 * master/secondary machine is not implemented here.
 *
 * <p><b>Lifecycle.</b> Call {@link #onConnected()} once the serial port is open to reset the link
 * state and arm the idle timer; then feed every inbound frame to {@link #onFrame(Ft12Frame)} and
 * send application data with {@link #sendAsdu(Asdu)}. A {@link Role#CLIENT} drives the link-reset
 * bring-up with {@link #startDataTransfer()}, which completes when the link becomes available; a
 * {@link Role#SERVER} reaches the available state by receiving the peer's reset-of-remote-link
 * frame. Call {@link #close()} when the transport goes away; it is idempotent.
 *
 * <p><b>Threading.</b> All mutable state is guarded by a single internal lock, and the timer
 * callbacks scheduled on the injected {@link ScheduledExecutorService} acquire that same lock, so a
 * caller may invoke any method from any thread. The {@link Output} and {@link Session.Events}
 * callbacks are always invoked while the lock is held; they must not call back into the link layer
 * and should not block.
 */
public final class Ft12LinkLayer implements Session {

  private static final Logger LOGGER = LoggerFactory.getLogger(Ft12LinkLayer.class);

  // Primary (PRM=1) function codes used by the balanced machine.
  private static final int FC_RESET_REMOTE_LINK = 0;
  private static final int FC_TEST_FUNCTION = 2;
  private static final int FC_USER_DATA = 3;
  private static final int FC_REQUEST_STATUS_OF_LINK = 9;

  // Secondary (PRM=0) function codes used by the balanced machine.
  private static final int FC_ACK = 0;
  private static final int FC_NACK = 1;
  private static final int FC_STATUS_OF_LINK = 11;
  private static final int FC_LINK_NOT_FUNCTIONING = 14;
  private static final int FC_LINK_NOT_IMPLEMENTED = 15;

  /** The role a station plays in the FT1.2 balanced link-reset bring-up. */
  public enum Role {

    /** The controlling station: initiates the link-reset bring-up and sends DIR=1 frames. */
    CLIENT,

    /** The controlled station: reacts to the peer's link-reset and sends DIR=0 frames. */
    SERVER
  }

  /** Sink for FT1.2 frames the link layer wants to transmit on the underlying transport. */
  public interface Output {

    /**
     * Transmits a frame produced by the link layer.
     *
     * <p>Invoked while the link-layer lock is held; implementations must not block or re-enter the
     * link layer.
     *
     * @param frame the frame to send.
     */
    void send(Ft12Frame frame);
  }

  /** The primary frame the link layer is currently awaiting a secondary confirmation for. */
  private enum PendingPrimary {

    /** A request-status-of-link (FC9) sent during bring-up, awaiting status-of-link (FC11). */
    BRING_UP_STATUS,

    /** A reset-of-remote-link (FC0) sent during bring-up, awaiting the positive acknowledgement. */
    BRING_UP_RESET,

    /** A user-data (FC3) frame sent, awaiting the positive acknowledgement. */
    USER_DATA,

    /** An idle request-status-of-link (FC9) keep-alive probe, awaiting status-of-link (FC11). */
    KEEPALIVE
  }

  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Signalled whenever the outbound send queue drains, so a parked publisher can re-check space.
   */
  private final Condition queueDrained = lock.newCondition();

  private final Role role;
  private final ScheduledExecutorService scheduler;
  private final Output output;
  private final Session.Events events;

  private final boolean dir;
  private final boolean useSingleCharAck;
  private final int linkAddress;
  private final long confirmTimeoutMillis;
  private final long repeatTimeoutMillis;
  private final long linkStateTimeoutMillis;
  private final int maxRetries;

  // Outbound send-queue bound and overflow policy (0 == unbounded, the client default).
  private final int maxOutboundQueue;
  private final OutboundQueuePolicy queuePolicy;

  private boolean closed;

  /**
   * Whether the link is reset and available for user data; equals {@link #isDataTransferStarted()}.
   */
  private boolean linkAvailable;

  // Primary process: the frames this station initiates and the FCB it stamps on them.
  private boolean nextFcb; // FCB for the next FCV=1 primary frame; true after a link reset
  private @Nullable PendingPrimary pending; // null when idle; else what we await a confirmation for
  private @Nullable Asdu
      pendingDataAsdu; // the in-flight FC3 ASDU, kept for verbatim retransmission
  private boolean pendingFcb; // the FCB stamped on the in-flight FCV=1 frame
  private int retryCount;
  private final ArrayDeque<Asdu> sendQueue = new ArrayDeque<>();
  private @Nullable CompletableFuture<Void> pendingStart;

  // Secondary process: the FCB this station expects on the peer's FCV=1 primary frames.
  private boolean secondaryReset; // true once the peer has reset our secondary
  private boolean expectedFcb; // expected FCB on the next FCV=1 primary frame from the peer
  private @Nullable Ft12Frame
      lastSecondaryResponse; // cached response, replayed on an unchanged FCB

  // Timer handles and the generation counters that invalidate stale dispatched tasks.
  private @Nullable ScheduledFuture<?> confirmFuture;
  private @Nullable ScheduledFuture<?> linkStateFuture;
  private int confirmGeneration;
  private int linkStateGeneration;

  /**
   * Creates a balanced link layer with an unbounded outbound send queue.
   *
   * @param role the role this station plays in the link-reset bring-up.
   * @param settings the FT1.2 link parameters; its {@linkplain LinkSettings#mode() mode} must be
   *     {@link LinkMode#BALANCED}.
   * @param scheduler the executor used to schedule the confirm and link-state timers; callbacks run
   *     under the link-layer lock.
   * @param output the sink for outbound frames.
   * @param events the callbacks for delivered ASDUs and lifecycle transitions.
   * @throws IllegalArgumentException if {@code settings} is not in balanced mode.
   */
  public Ft12LinkLayer(
      Role role,
      LinkSettings settings,
      ScheduledExecutorService scheduler,
      Output output,
      Session.Events events) {

    this(role, settings, scheduler, output, events, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  /**
   * Creates a balanced link layer with a bounded outbound send queue and an overflow policy.
   *
   * @param role the role this station plays in the link-reset bring-up.
   * @param settings the FT1.2 link parameters; its {@linkplain LinkSettings#mode() mode} must be
   *     {@link LinkMode#BALANCED}.
   * @param scheduler the executor used to schedule the confirm and link-state timers; callbacks run
   *     under the link-layer lock.
   * @param output the sink for outbound frames.
   * @param events the callbacks for delivered ASDUs and lifecycle transitions.
   * @param maxOutboundQueue the maximum number of ASDUs held in the send queue while a frame is
   *     outstanding, or {@code 0} for an unbounded queue.
   * @param queuePolicy the action taken when a bounded queue overflows.
   * @throws IllegalArgumentException if {@code settings} is not in balanced mode, or if {@code
   *     maxOutboundQueue} is negative.
   */
  public Ft12LinkLayer(
      Role role,
      LinkSettings settings,
      ScheduledExecutorService scheduler,
      Output output,
      Session.Events events,
      int maxOutboundQueue,
      OutboundQueuePolicy queuePolicy) {

    this.role = Objects.requireNonNull(role, "role");
    Objects.requireNonNull(settings, "settings");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.output = Objects.requireNonNull(output, "output");
    this.events = Objects.requireNonNull(events, "events");
    if (settings.mode() != LinkMode.BALANCED) {
      throw new IllegalArgumentException(
          "Ft12LinkLayer supports BALANCED mode only, got: " + settings.mode());
    }
    if (maxOutboundQueue < 0) {
      throw new IllegalArgumentException("maxOutboundQueue must be >= 0: " + maxOutboundQueue);
    }
    this.maxOutboundQueue = maxOutboundQueue;
    this.queuePolicy = Objects.requireNonNull(queuePolicy, "queuePolicy");

    this.dir = role == Role.CLIENT;
    this.useSingleCharAck = settings.useSingleCharAck();
    this.linkAddress = settings.linkAddress();
    this.confirmTimeoutMillis = settings.confirmTimeout().toMillis();
    this.repeatTimeoutMillis = settings.repeatTimeout().toMillis();
    this.linkStateTimeoutMillis = settings.linkStateTimeout().toMillis();
    this.maxRetries = settings.maxRetries();

    // Leave the link layer in a valid reset state so it is usable even before onConnected().
    resetState();
  }

  /**
   * Resets the link layer for a freshly established transport connection.
   *
   * <p>The primary and secondary FCB state return to their post-reset defaults, the link becomes
   * unavailable, all pending state is cleared, and the idle link-state timer is armed. The
   * data-transfer state change is not reported from here; it is reported when the link-reset
   * handshake reaches the available state.
   */
  @Override
  public void onConnected() {
    lock.lock();
    try {
      closed = false;
      resetState();
      cancelAllTimers();
      armLinkStateTimer();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Handles a single inbound FT1.2 frame according to its format and this station's role.
   *
   * <p>A single-character frame and a secondary (response) frame are matched against the
   * outstanding primary frame this station is awaiting a confirmation for; a primary (request)
   * frame is handled by this station's secondary process, which acknowledges it and, for user data,
   * delivers the carried ASDU. Any inbound frame counts as link activity and restarts the idle
   * timer.
   *
   * @param frame the inbound frame.
   */
  public void onFrame(Ft12Frame frame) {
    Objects.requireNonNull(frame, "frame");
    lock.lock();
    try {
      if (closed) {
        return;
      }
      // Any received frame is activity: restart the idle keep-alive timer.
      armLinkStateTimer();

      if (frame instanceof Ft12Frame.SingleChar) {
        handlePositiveAck();
      } else if (frame instanceof Ft12Frame.FixedLength fixed) {
        dispatchControlFrame(fixed.control(), null);
      } else if (frame instanceof Ft12Frame.Variable variable) {
        dispatchControlFrame(variable.control(), variable.asdu());
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Starts user-data transfer by driving the FT1.2 link-reset bring-up (CLIENT role only).
   *
   * <p>Sends a request-status-of-link frame and returns a stage that completes when the bring-up
   * handshake reaches the link-available state. If the peer does not confirm a bring-up frame
   * within the configured retries the link closes and the stage — together with the {@link
   * Session.Events#onClosed(Throwable)} callback — completes exceptionally with a {@link
   * ProtocolTimeoutException}.
   *
   * @return a stage that completes when the link is available, or completes exceptionally on
   *     timeout, close, or misuse.
   * @throws IllegalStateException if called on a {@link Role#SERVER} station.
   */
  @Override
  public CompletionStage<Void> startDataTransfer() {
    lock.lock();
    try {
      if (role != Role.CLIENT) {
        throw new IllegalStateException("startDataTransfer is only valid for a CLIENT session");
      }
      if (closed) {
        return failedFuture(new ConnectionClosedException("session is closed"));
      }
      if (linkAvailable) {
        return CompletableFuture.completedFuture(null);
      }
      if (pendingStart != null) {
        return pendingStart;
      }
      CompletableFuture<Void> future = new CompletableFuture<>();
      pendingStart = future;
      beginBringUp();
      return future;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops user-data transfer (CLIENT role only).
   *
   * <p>FT1.2 has no stop-data service, so this completes immediately and leaves the link available;
   * it exists to satisfy the {@link Session} contract symmetrically with {@link
   * #startDataTransfer()}.
   *
   * @return a stage that completes immediately, or exceptionally if the session is closed.
   * @throws IllegalStateException if called on a {@link Role#SERVER} station.
   */
  @Override
  public CompletionStage<Void> stopDataTransfer() {
    lock.lock();
    try {
      if (role != Role.CLIENT) {
        throw new IllegalStateException("stopDataTransfer is only valid for a CLIENT session");
      }
      if (closed) {
        return failedFuture(new ConnectionClosedException("session is closed"));
      }
      return CompletableFuture.completedFuture(null);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Reports whether the link is reset and available for user-data transfer.
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
   * Sends an application ASDU as an FT1.2 user-data frame, honoring the window of one.
   *
   * <p>The balanced primary process is stop-and-wait with a single outstanding frame: if a frame is
   * already awaiting confirmation, or the link is not yet available, the ASDU is queued and
   * transmitted later when the in-flight frame is confirmed or the link becomes available. Queued
   * ASDUs are sent in submission order.
   *
   * @param asdu the application ASDU to send.
   */
  @Override
  public void sendAsdu(Asdu asdu) {
    Objects.requireNonNull(asdu, "asdu");
    lock.lock();
    try {
      if (closed) {
        return;
      }
      // Enforce the configured outbound queue bound (0 == unbounded). The bound applies to ASDUs
      // that cannot be transmitted immediately; flushSendQueue below sends one if the window is
      // open. BLOCK is never honored by parking here because that would block the caller under the
      // lock; the publisher is expected to have awaited capacity via awaitSendCapacity(...) first.
      // As a last-resort guard, a full BLOCK queue drops the newest so the bound is never exceeded.
      if (maxOutboundQueue > 0 && sendQueue.size() >= maxOutboundQueue) {
        switch (queuePolicy) {
          case DROP_OLDEST -> {
            sendQueue.pollFirst();
            sendQueue.addLast(asdu);
          }
          case DROP_NEWEST, BLOCK -> {
            // Drop the newly offered ASDU; keep the already accepted history.
          }
        }
      } else {
        sendQueue.addLast(asdu);
      }
      flushSendQueue();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Awaits free capacity in the outbound send queue, off the link layer's hot path, for the {@link
   * OutboundQueuePolicy#BLOCK} policy.
   *
   * <p>Called by a publishing application thread before offering another ASDU when the queue is
   * bounded and the policy is {@code BLOCK}. It parks on the drain signal until the queue depth
   * falls below the bound, the session closes, or the deadline elapses.
   *
   * @param timeoutMillis the maximum time to wait, in milliseconds.
   * @return {@code true} if capacity is available (or the queue is unbounded / session closed),
   *     {@code false} if the wait timed out with the queue still full.
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
      while (!closed && sendQueue.size() >= maxOutboundQueue) {
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
   * Returns the number of ASDUs currently waiting in the outbound send queue (not yet handed to the
   * transport as a user-data frame).
   *
   * @return the pending send-queue depth.
   */
  @Override
  public int pendingSendCount() {
    lock.lock();
    try {
      return sendQueue.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Closes the link layer.
   *
   * <p>Cancels all timers, fails any pending bring-up future with a {@link
   * ConnectionClosedException}, and marks the session closed. This method is idempotent and does
   * not invoke {@link Session.Events#onClosed(Throwable)} (that callback is reserved for
   * self-initiated closes triggered by protocol errors and timeouts).
   */
  @Override
  public void close() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      cancelAllTimers();
      queueDrained.signalAll();
      failPendingStart(new ConnectionClosedException("session closed"));
    } finally {
      lock.unlock();
    }
  }

  // --- Bring-up (lock held) -------------------------------------------------------------------

  private void beginBringUp() {
    pending = PendingPrimary.BRING_UP_STATUS;
    retryCount = 0;
    sendPrimaryNoData(FC_REQUEST_STATUS_OF_LINK);
    // output.send(...) may have re-entered and closed the session (a synchronous transport-send
    // failure, or a synchronously delivered response that completed the bring-up); if so, do not
    // arm
    // a timer on a closed session.
    if (closed) {
      return;
    }
    armConfirmTimer(confirmTimeoutMillis);
  }

  private void bringUpComplete() {
    cancelConfirmTimer();
    pending = null;
    retryCount = 0;
    // A fresh reset: the primary FCB starts at 1 and the secondary expects 0, on both ends.
    nextFcb = true;
    secondaryReset = true;
    expectedFcb = false;
    lastSecondaryResponse = null;
    if (!linkAvailable) {
      linkAvailable = true;
      events.onDataTransferStateChanged(true);
    }
    completePendingStart();
    flushSendQueue();
  }

  // --- Inbound dispatch (lock held) -----------------------------------------------------------

  private void dispatchControlFrame(LinkControlField control, @Nullable Asdu asdu) {
    if (control.prm()) {
      // The peer initiated this frame: our secondary process answers it.
      handlePrimaryFromPeer(control, asdu);
    } else {
      // A response to the primary frame we are awaiting a confirmation for.
      handleSecondaryResponse(control);
    }
  }

  /**
   * Handles the {@code 0xE5} single-character positive acknowledgement of our outstanding frame.
   */
  private void handlePositiveAck() {
    PendingPrimary current = pending;
    if (current == null) {
      return; // stale acknowledgement; nothing outstanding
    }
    switch (current) {
      case BRING_UP_RESET -> bringUpComplete();
      case USER_DATA -> dataAcked();
      case BRING_UP_STATUS, KEEPALIVE -> {
        // 0xE5 is not a valid status-of-link reply; ignore and let the confirm timer retry.
      }
    }
  }

  private void handleSecondaryResponse(LinkControlField control) {
    PendingPrimary current = pending;
    if (current == null) {
      return; // stale response; nothing outstanding
    }
    int fc = control.functionCode();
    switch (current) {
      case BRING_UP_STATUS -> {
        if (fc == FC_STATUS_OF_LINK) {
          // The peer's link is reachable: reset it, then await the positive acknowledgement.
          pending = PendingPrimary.BRING_UP_RESET;
          retryCount = 0;
          sendPrimaryNoData(FC_RESET_REMOTE_LINK);
          if (closed) {
            return;
          }
          armConfirmTimer(confirmTimeoutMillis);
        } else if (isLinkServiceFailure(fc)) {
          closeWithError(linkServiceFailure(fc));
        } else {
          LOGGER.debug("unexpected response to request-status-of-link: FC{}", fc);
        }
      }
      case BRING_UP_RESET -> {
        if (fc == FC_ACK) {
          bringUpComplete();
        } else if (isLinkServiceFailure(fc)) {
          closeWithError(linkServiceFailure(fc));
        } else {
          LOGGER.debug("unexpected response to reset-of-remote-link: FC{}", fc);
        }
      }
      case USER_DATA -> {
        if (fc == FC_ACK) {
          dataAcked();
        } else if (fc == FC_NACK) {
          // The secondary is busy or did not accept the data; let the confirm timer retransmit.
          LOGGER.debug("secondary NACK (FC1) for user data; will retransmit on confirm timeout");
        } else if (isLinkServiceFailure(fc)) {
          closeWithError(linkServiceFailure(fc));
        } else {
          LOGGER.debug("unexpected response to user-data: FC{}", fc);
        }
      }
      case KEEPALIVE -> {
        if (fc == FC_STATUS_OF_LINK) {
          cancelConfirmTimer();
          pending = null;
          retryCount = 0;
          // The keep-alive occupied the single-frame window; an ASDU submitted while it was
          // outstanding is now eligible to drain, so resume the send queue.
          flushSendQueue();
        } else if (isLinkServiceFailure(fc)) {
          closeWithError(linkServiceFailure(fc));
        } else {
          LOGGER.debug("unexpected response to keep-alive request-status-of-link: FC{}", fc);
        }
      }
    }
  }

  private void dataAcked() {
    cancelConfirmTimer();
    pending = null;
    pendingDataAsdu = null;
    nextFcb = !nextFcb;
    retryCount = 0;
    flushSendQueue();
  }

  private void handlePrimaryFromPeer(LinkControlField control, @Nullable Asdu asdu) {
    int fc = control.functionCode();
    switch (fc) {
      case FC_REQUEST_STATUS_OF_LINK ->
          // Answer a link-status request with our status-of-link; no reset is required.
          output.send(secondaryFrame(FC_STATUS_OF_LINK));
      case FC_RESET_REMOTE_LINK -> handleResetFromPeer();
      case FC_TEST_FUNCTION -> handleTestFunction(control);
      case FC_USER_DATA -> handleUserData(control, asdu);
      default ->
          // No other primary function code is expected in balanced point-to-point; tell the peer
          // the
          // service is not implemented.
          output.send(secondaryFrame(FC_LINK_NOT_IMPLEMENTED));
    }
  }

  private void handleResetFromPeer() {
    // The peer reset the link: reset our secondary and primary FCB state for a fresh link.
    secondaryReset = true;
    expectedFcb = false;
    nextFcb = true;
    lastSecondaryResponse = null;
    if (!linkAvailable) {
      linkAvailable = true;
      events.onDataTransferStateChanged(true);
    }
    output.send(makeAck());
    if (closed) {
      return;
    }
    // The link is now available in both directions: flush any user data this station has queued.
    flushSendQueue();
  }

  private void handleTestFunction(LinkControlField control) {
    // Test-function-for-link is FCB-checked like user data but carries no ASDU.
    boolean fcb = control.fcb();
    if (fcb != expectedFcb) {
      expectedFcb = fcb;
    }
    Ft12Frame ack = makeAck();
    lastSecondaryResponse = ack;
    output.send(ack);
  }

  private void handleUserData(LinkControlField control, @Nullable Asdu asdu) {
    if (!secondaryReset) {
      // User data before a link reset is irregular; accept it leniently but note the missing reset.
      LOGGER.debug("received user data before a link reset; accepting leniently");
    }
    boolean fcb = control.fcb();
    if (fcb == expectedFcb) {
      // Unchanged FCB: this is a retransmission. Replay the cached response without re-delivering.
      Ft12Frame cached = lastSecondaryResponse;
      if (cached != null) {
        output.send(cached);
      } else {
        Ft12Frame ack = makeAck();
        lastSecondaryResponse = ack;
        output.send(ack);
      }
    } else {
      // Changed FCB: a new frame. Deliver, acknowledge, and advance the expected FCB.
      expectedFcb = fcb;
      Ft12Frame ack = makeAck();
      lastSecondaryResponse = ack;
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
  }

  // --- Outbound helpers (lock held) -----------------------------------------------------------

  private void flushSendQueue() {
    // Window of one: at most a single outstanding primary frame, and only once the link is
    // available.
    if (!linkAvailable || pending != null) {
      return;
    }
    if (sendQueue.isEmpty()) {
      return;
    }
    Asdu asdu = sendQueue.removeFirst();
    // A queue slot freed up; wake any publisher parked on capacity (BLOCK policy).
    queueDrained.signalAll();
    pendingDataAsdu = asdu;
    pendingFcb = nextFcb;
    pending = PendingPrimary.USER_DATA;
    retryCount = 0;
    output.send(
        new Ft12Frame.Variable(
            LinkControlField.primary(dir, pendingFcb, true, FC_USER_DATA), linkAddress, asdu));
    // output.send(...) may have synchronously confirmed and cleared the pending frame (and possibly
    // sent the next queued frame), or closed the session; re-check before arming on a stale state.
    if (closed) {
      return;
    }
    armConfirmTimer(confirmTimeoutMillis);
    armLinkStateTimer();
  }

  private void sendPrimaryNoData(int functionCode) {
    // FC0 (reset) and FC9 (request status) both have FCV=0, so the FCB is not significant.
    output.send(
        new Ft12Frame.FixedLength(
            LinkControlField.primary(dir, false, false, functionCode), linkAddress));
  }

  private Ft12Frame secondaryFrame(int functionCode) {
    return new Ft12Frame.FixedLength(
        LinkControlField.secondary(dir, false, false, functionCode), linkAddress);
  }

  private Ft12Frame makeAck() {
    if (useSingleCharAck) {
      return new Ft12Frame.SingleChar();
    }
    return secondaryFrame(FC_ACK);
  }

  private void retransmitPending() {
    PendingPrimary current = pending;
    if (current == null) {
      return;
    }
    switch (current) {
      case BRING_UP_STATUS, KEEPALIVE -> sendPrimaryNoData(FC_REQUEST_STATUS_OF_LINK);
      case BRING_UP_RESET -> sendPrimaryNoData(FC_RESET_REMOTE_LINK);
      case USER_DATA -> {
        Asdu asdu = Objects.requireNonNull(pendingDataAsdu, "pendingDataAsdu");
        output.send(
            new Ft12Frame.Variable(
                LinkControlField.primary(dir, pendingFcb, true, FC_USER_DATA), linkAddress, asdu));
      }
    }
  }

  // --- Timers (lock held) ---------------------------------------------------------------------

  private void armConfirmTimer(long delayMillis) {
    cancelConfirmTimer();
    int generation = ++confirmGeneration;
    confirmFuture = schedule(() -> onConfirmExpired(generation), delayMillis);
  }

  private void cancelConfirmTimer() {
    // Bump the generation so an already-dispatched task (possibly blocked on the lock) is a no-op.
    confirmGeneration++;
    if (confirmFuture != null) {
      confirmFuture.cancel(false);
      confirmFuture = null;
    }
  }

  private void armLinkStateTimer() {
    cancelLinkStateTimer();
    int generation = ++linkStateGeneration;
    linkStateFuture = schedule(() -> onLinkStateExpired(generation), linkStateTimeoutMillis);
  }

  private void cancelLinkStateTimer() {
    linkStateGeneration++;
    if (linkStateFuture != null) {
      linkStateFuture.cancel(false);
      linkStateFuture = null;
    }
  }

  private void cancelAllTimers() {
    cancelConfirmTimer();
    cancelLinkStateTimer();
  }

  private void onConfirmExpired(int generation) {
    lock.lock();
    try {
      // Ignore a stale task: the session closed, or the timer was re-armed/cancelled after this
      // task
      // was dispatched (it may have been blocked on the lock while a fresh timer was armed).
      if (closed || generation != confirmGeneration) {
        return;
      }
      confirmFuture = null;
      if (pending == null) {
        // The outstanding frame was confirmed synchronously after this timer was armed; nothing to
        // do.
        return;
      }
      if (retryCount < maxRetries) {
        retryCount++;
        retransmitPending();
        // The retransmission may have been confirmed synchronously or closed the session.
        if (closed) {
          return;
        }
        armConfirmTimer(repeatTimeoutMillis);
      } else {
        closeWithError(
            new ProtocolTimeoutException(
                "no link-layer confirmation after " + maxRetries + " retransmissions"));
      }
    } finally {
      lock.unlock();
    }
  }

  private void onLinkStateExpired(int generation) {
    lock.lock();
    try {
      if (closed || generation != linkStateGeneration) {
        return;
      }
      linkStateFuture = null;
      if (linkAvailable && pending == null && sendQueue.isEmpty()) {
        // The link has been idle: probe it with a request-status-of-link keep-alive.
        pending = PendingPrimary.KEEPALIVE;
        retryCount = 0;
        sendPrimaryNoData(FC_REQUEST_STATUS_OF_LINK);
        if (closed) {
          return;
        }
        armConfirmTimer(confirmTimeoutMillis);
      } else {
        // Not idle (link not yet up, a frame is outstanding, or data is queued): the confirm timer
        // governs liveness while a frame is outstanding; keep the idle clock ticking otherwise.
        armLinkStateTimer();
      }
    } finally {
      lock.unlock();
    }
  }

  private ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
    return scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
  }

  // --- Closure (lock held) --------------------------------------------------------------------

  private void closeWithError(Throwable cause) {
    if (closed) {
      return;
    }
    closed = true;
    cancelAllTimers();
    queueDrained.signalAll();
    failPendingStart(cause);
    events.onClosed(cause);
  }

  private void failPendingStart(Throwable cause) {
    CompletableFuture<Void> start = pendingStart;
    pendingStart = null;
    if (start != null) {
      start.completeExceptionally(cause);
    }
  }

  private void completePendingStart() {
    CompletableFuture<Void> start = pendingStart;
    pendingStart = null;
    if (start != null) {
      // null is the only valid completion value for a CompletableFuture<Void>.
      //noinspection DataFlowIssue
      start.complete(null);
    }
  }

  // --- Misc -----------------------------------------------------------------------------------

  private void resetState() {
    linkAvailable = false;
    nextFcb = true;
    pending = null;
    pendingDataAsdu = null;
    pendingFcb = false;
    retryCount = 0;
    sendQueue.clear();
    secondaryReset = false;
    expectedFcb = false;
    lastSecondaryResponse = null;
  }

  private static boolean isLinkServiceFailure(int functionCode) {
    return functionCode == FC_LINK_NOT_FUNCTIONING || functionCode == FC_LINK_NOT_IMPLEMENTED;
  }

  private static Iec60870Exception linkServiceFailure(int functionCode) {
    String detail =
        functionCode == FC_LINK_NOT_FUNCTIONING
            ? "link service not functioning"
            : "link service not implemented";
    return new Iec60870Exception("peer reported " + detail + " (FC" + functionCode + ")");
  }

  private static CompletableFuture<Void> failedFuture(Throwable cause) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    future.completeExceptionally(cause);
    return future;
  }
}
