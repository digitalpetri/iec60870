package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.session.Session;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
 * The unbalanced FT1.2 master (primary) link state machine for IEC 60870-5-101, the multi-drop
 * polling peer of the {@link BalancedEngine}.
 *
 * <p>An {@code UnbalancedMasterEngine} owns a half-duplex serial bus shared by one master (this
 * station, always the primary) and one or more configured secondary stations (slaves). Because a
 * single transmission medium is shared, the master enforces a <em>global</em> stop-and-wait
 * discipline: at most one transaction is outstanding on the bus at any time (a single {@link
 * Pending pending} descriptor). The frame count bit (FCB) sequence, by contrast, is tracked <em>per
 * secondary</em>: each slave has its own {@link SlaveState} carrying its next FCB, its link state,
 * and its data-flow-control (DFC) back-pressure flag.
 *
 * <p><b>Per-slave bring-up.</b> Each configured slave is brought up independently with a
 * request-status-of-link (FC9) followed, on a status-of-link (FC11) reply, by a
 * reset-of-remote-link (FC0); the positive acknowledgement of the reset transitions that slave to
 * {@link LinkState#AVAILABLE available} and restarts its FCB at {@code 1}. Bring-up is scheduled
 * <em>fairly</em>: it is the lowest-priority bus activity (behind commands and polls to slaves that
 * are already available), it round-robins across the not-yet-reset slaves, and the request-status
 * retransmissions of an unresponsive slave <em>release the bus</em> between probes whenever other
 * work could run, so a single dead slave cannot monopolize the shared bus for its whole retransmit
 * budget while polls and commands to healthy slaves wait. The dead slave is still degraded to
 * {@link LinkState#ERROR error} once its per-slave probe budget is exhausted.
 *
 * <p><b>Polling.</b> Once available, slaves are polled for class-2 data on the configured
 * {@linkplain LinkSettings.PollConfig#pollInterval() cadence} with request-class-2 (FC11, FCV=1)
 * frames, round robin across the available slaves. A response carrying the access-demand bit (ACD)
 * escalates immediately to request-class-1 (FC10) to drain the slave's high-priority data, bounded
 * so a busy slave cannot monopolize the bus. A response carrying the data-flow-control bit (DFC)
 * marks the slave back-pressured, which suspends sending it user data (but not polling it) until a
 * later response clears DFC.
 *
 * <p><b>Commands.</b> Application ASDUs submitted with {@link #sendAsdu(Asdu)} are placed on a
 * global command queue and addressed to the slave whose <em>link address equals the ASDU's common
 * address</em> (the frozen façade mapping); they are sent as send/confirm user data (FC3, FCV=1).
 * An ASDU whose common address equals the {@linkplain LinkSettings#broadcastAddress() broadcast
 * address} is sent as send/no-reply (FC4) to all stations and is not acknowledged.
 *
 * <p><b>Per-slave degradation.</b> When a transaction goes unacknowledged after the configured
 * retries the offending <em>slave</em> is degraded to {@link LinkState#ERROR error} and the
 * transaction is dropped; the bus and the {@link Session} stay open so a single dead slave cannot
 * kill the link. Unlike {@link BalancedEngine}, this engine therefore never self-closes the session
 * on a protocol timeout — only a transport loss (signalled by the binding calling {@link #close()})
 * ends the session.
 *
 * <p>This engine is the {@link Ft12LinkLayer.Role#CLIENT}-role realization of {@link
 * LinkMode#UNBALANCED} and is selected by the {@link Ft12LinkLayer} dispatcher; it is
 * transport-agnostic, handing outbound frames to the supplied {@link Ft12LinkLayer.Output} and
 * reporting delivered ASDUs through the supplied {@link Session.Events}.
 *
 * <p><b>Threading.</b> All mutable state is guarded by a single internal lock, and the timer
 * callbacks scheduled on the injected {@link ScheduledExecutorService} acquire that same lock, so a
 * caller may invoke any method from any thread. The {@link Ft12LinkLayer.Output} and {@link
 * Session.Events} callbacks are always invoked while the lock is held; they must not call back into
 * the engine and should not block.
 */
final class UnbalancedMasterEngine implements Ft12Engine {

  private static final Logger LOGGER = LoggerFactory.getLogger(UnbalancedMasterEngine.class);

  // Primary (PRM=1) function codes the master sends. Unbalanced transmission uses RES (bit 7 = 0)
  // rather than DIR, so every primary control field is built with dir=false.
  private static final int FC_RESET_REMOTE_LINK = 0;
  private static final int FC_USER_DATA = 3;
  private static final int FC_USER_DATA_NO_REPLY = 4;
  private static final int FC_REQUEST_STATUS_OF_LINK = 9;
  private static final int FC_REQUEST_CLASS_1_DATA = 10;
  private static final int FC_REQUEST_CLASS_2_DATA = 11;

  // Secondary (PRM=0) function codes a slave returns.
  private static final int FC_ACK = 0;
  private static final int FC_NACK = 1;
  private static final int FC_RESPOND_USER_DATA = 8;
  private static final int FC_RESPOND_NO_DATA = 9;
  private static final int FC_STATUS_OF_LINK = 11;

  /**
   * The maximum number of consecutive class-1 drain escalations triggered by a slave's
   * access-demand bit before the master yields the bus. Bounds ACD escalation so a slave that keeps
   * asserting ACD cannot starve the rest of the bus; any remaining class-1 data is picked up on the
   * slave's next class-2 poll.
   */
  private static final int MAX_ACD_DRAIN = 16;

  /** The bring-up / availability state of a single configured secondary station. */
  private enum LinkState {

    /** Not yet reset: the master still owes this slave its request-status/reset bring-up. */
    UNRESET,

    /** Reset and reachable: eligible for polling and user data. */
    AVAILABLE,

    /** Unreachable after the configured retries: skipped until the next {@link #onConnected()}. */
    ERROR
  }

  /** The kind of single outstanding bus transaction the master is awaiting a confirmation for. */
  private enum Kind {

    /** A request-status-of-link (FC9) sent during bring-up, awaiting status-of-link (FC11). */
    RESET_STATUS,

    /** A reset-of-remote-link (FC0) sent during bring-up, awaiting the positive acknowledgement. */
    RESET,

    /** A request-class-2 (FC11, FCV=1) poll, awaiting user data (FC8), no data (FC9), or an ack. */
    POLL_CLASS2,

    /**
     * A request-class-1 (FC10, FCV=1) drain, awaiting user data (FC8), no data (FC9), or an ack.
     */
    POLL_CLASS1,

    /** A send/confirm user-data (FC3, FCV=1) frame, awaiting the positive acknowledgement. */
    USER_DATA
  }

  /** The mutable per-secondary state: FCB sequence, link availability, and DFC back-pressure. */
  private static final class SlaveState {

    private LinkState linkState = LinkState.UNRESET;
    private boolean nextFcb = true; // FCB for this slave's next FCV=1 frame; true after a reset
    private boolean dfc; // the slave's last-reported data-flow-control (receive-buffer-full) bit

    // Unanswered request-status-of-link (FC9) bring-up transmissions accumulated for this slave.
    // Counted per slave (not on the shared retryCount) so that bring-up can yield the bus between
    // attempts — interleaving service to other slaves — while still degrading this slave to ERROR
    // once the count exceeds maxRetries.
    private int bringUpRetries;
  }

  /**
   * The single outstanding bus transaction (window of one across the whole bus).
   *
   * @param slaveAddress the secondary station this transaction is directed at.
   * @param kind the kind of transaction awaiting a confirmation.
   * @param asdu the in-flight user-data ASDU, kept for verbatim retransmission, or {@code null} for
   *     a transaction that carries no ASDU.
   * @param fcb the frame count bit stamped on this FCV=1 frame, replayed on retransmission.
   * @param acdDrain the number of class-1 drains this access-demand escalation chain has performed.
   */
  private record Pending(
      int slaveAddress, Kind kind, @Nullable Asdu asdu, boolean fcb, int acdDrain) {}

  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Signalled whenever the outbound command queue drains, so a parked publisher can re-check space.
   */
  private final Condition queueDrained = lock.newCondition();

  private final ScheduledExecutorService scheduler;
  private final Ft12LinkLayer.Output output;
  private final Session.Events events;

  private final int broadcastAddress;
  private final long confirmTimeoutMillis;
  private final long repeatTimeoutMillis;
  private final int maxRetries;
  private final long pollIntervalMillis;
  private final List<Integer> slaveAddresses;

  // Outbound command-queue bound and overflow policy (0 == unbounded).
  private final int maxOutboundQueue;
  private final OutboundQueuePolicy queuePolicy;

  // Per-secondary state, keyed by link address; rebuilt (all UNRESET) on every (re)connect.
  private final Map<Integer, SlaveState> slaves = new HashMap<>();

  // The global command queue of ASDUs awaiting transmission as user data.
  private final ArrayDeque<Asdu> commandQueue = new ArrayDeque<>();

  private boolean closed;
  private boolean started; // == isDataTransferStarted(); true while the poller runs

  private @Nullable Pending pending; // null when the bus is idle; else the outstanding transaction
  private int retryCount; // retransmissions already attempted for the current pending transaction

  private boolean pollPending; // a class-2 poll is owed (set on a poll tick, consumed by pump())
  private int pollCursor; // round-robin index into slaveAddresses for the next poll
  private int bringUpCursor; // round-robin index into slaveAddresses for the next bring-up

  // Timer handles and the generation counters that invalidate stale dispatched tasks.
  private @Nullable ScheduledFuture<?> confirmFuture;
  private int confirmGeneration;
  private @Nullable ScheduledFuture<?> pollFuture;
  private int pollGeneration;

  /**
   * Creates an unbalanced master link layer.
   *
   * @param settings the FT1.2 link parameters; its {@linkplain LinkSettings#mode() mode} must be
   *     {@link LinkMode#UNBALANCED}. The configured secondary stations and the poll cadence are
   *     read from {@link LinkSettings#pollConfig()} (an empty slave list polled every {@code 1000
   *     ms} when it is absent).
   * @param scheduler the executor used to schedule the confirm and poll timers; callbacks run under
   *     the engine lock.
   * @param output the sink for outbound frames.
   * @param events the callbacks for delivered ASDUs and lifecycle transitions.
   * @param maxOutboundQueue the maximum number of ASDUs held in the command queue, or {@code 0} for
   *     an unbounded queue.
   * @param queuePolicy the action taken when a bounded command queue overflows.
   * @throws IllegalArgumentException if {@code settings} is not in unbalanced mode, or if {@code
   *     maxOutboundQueue} is negative.
   */
  UnbalancedMasterEngine(
      LinkSettings settings,
      ScheduledExecutorService scheduler,
      Ft12LinkLayer.Output output,
      Session.Events events,
      int maxOutboundQueue,
      OutboundQueuePolicy queuePolicy) {

    Objects.requireNonNull(settings, "settings");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.output = Objects.requireNonNull(output, "output");
    this.events = Objects.requireNonNull(events, "events");
    if (settings.mode() != LinkMode.UNBALANCED) {
      throw new IllegalArgumentException(
          "UnbalancedMasterEngine supports UNBALANCED mode only, got: " + settings.mode());
    }
    if (maxOutboundQueue < 0) {
      throw new IllegalArgumentException("maxOutboundQueue must be >= 0: " + maxOutboundQueue);
    }
    this.maxOutboundQueue = maxOutboundQueue;
    this.queuePolicy = Objects.requireNonNull(queuePolicy, "queuePolicy");

    this.broadcastAddress = settings.broadcastAddress();
    this.confirmTimeoutMillis = settings.confirmTimeout().toMillis();
    this.repeatTimeoutMillis = settings.repeatTimeout().toMillis();
    this.maxRetries = settings.maxRetries();

    LinkSettings.PollConfig pollConfig = settings.pollConfig();
    this.slaveAddresses = pollConfig != null ? List.copyOf(pollConfig.slaveAddresses()) : List.of();
    this.pollIntervalMillis = pollConfig != null ? pollConfig.pollInterval().toMillis() : 1000L;

    // Leave the engine in a valid reset state so it is usable even before onConnected().
    resetState();
  }

  /**
   * Resets the master for a freshly established transport connection.
   *
   * <p>Every configured slave returns to {@link LinkState#UNRESET}, the bus goes idle, the command
   * queue and poll cursor are cleared, and all timers are cancelled. The poller is not started
   * here; a {@link #startDataTransfer()} call drives bring-up and arms the poll timer. No
   * data-transfer state change is reported from here.
   */
  @Override
  public void onConnected() {
    lock.lock();
    try {
      closed = false;
      resetState();
      cancelAllTimers();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Handles a single inbound FT1.2 frame.
   *
   * <p>A secondary station never initiates, so every inbound frame is expected to be a secondary
   * (response) frame matched against the master's outstanding transaction; a single-character
   * {@code 0xE5} frame is a positive acknowledgement, and an unexpected primary frame is ignored.
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
      if (frame instanceof Ft12Frame.SingleChar) {
        // 0xE5 is a positive acknowledgement with ACD=0 and DFC=0 implied.
        handleSecondaryResponse(FC_ACK, false, false, -1, true, null);
      } else if (frame instanceof Ft12Frame.FixedLength fixed) {
        LinkControlField control = fixed.control();
        if (acceptSecondary(control)) {
          handleSecondaryResponse(
              control.functionCode(),
              control.acd(),
              control.dfc(),
              fixed.linkAddress(),
              false,
              null);
        }
      } else if (frame instanceof Ft12Frame.Variable variable) {
        LinkControlField control = variable.control();
        if (acceptSecondary(control)) {
          handleSecondaryResponse(
              control.functionCode(),
              control.acd(),
              control.dfc(),
              variable.linkAddress(),
              false,
              variable.asdu());
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Starts user-data transfer by beginning per-slave bring-up and arming the class-2 poll timer.
   *
   * <p>The returned stage completes as soon as the poller is armed; it does not block on the
   * bring-up of individual slaves, whose availability is tracked per-slave and may complete (or
   * fail to {@link LinkState#ERROR}) afterward without affecting the bus.
   *
   * @return a stage that completes once the poller is armed, or completes exceptionally if the
   *     session is closed.
   */
  @Override
  public CompletionStage<Void> startDataTransfer() {
    lock.lock();
    try {
      if (closed) {
        return failedFuture(new ConnectionClosedException("session is closed"));
      }
      if (started) {
        return CompletableFuture.completedFuture(null);
      }
      started = true;
      pollPending = false;
      pollCursor = 0;
      armPollTimer();
      // Kick off bring-up immediately; the poll timer drives the steady-state class-2 cadence.
      pump();
      if (closed) {
        return failedFuture(new ConnectionClosedException("session closed during start"));
      }
      return CompletableFuture.completedFuture(null);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops user-data transfer by stopping the class-2 poller.
   *
   * <p>The poll timer is cancelled and no further transactions are initiated; any single
   * transaction already on the bus is left to settle. This completes immediately.
   *
   * @return a stage that completes immediately, or exceptionally if the session is closed.
   */
  @Override
  public CompletionStage<Void> stopDataTransfer() {
    lock.lock();
    try {
      if (closed) {
        return failedFuture(new ConnectionClosedException("session is closed"));
      }
      started = false;
      pollPending = false;
      cancelPollTimer();
      return CompletableFuture.completedFuture(null);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Reports whether the class-2 poller is running.
   *
   * @return {@code true} if data transfer (polling) has started.
   */
  @Override
  public boolean isDataTransferStarted() {
    lock.lock();
    try {
      return started;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Submits an application ASDU for transmission to the slave whose link address equals the ASDU's
   * common address.
   *
   * <p>The ASDU is appended to the global command queue (honoring the configured bound and overflow
   * policy) and dispatched when the bus is free and the target slave is available; an ASDU
   * addressed to the broadcast address is sent as a send/no-reply frame. Queued ASDUs to the same
   * slave are sent in submission order; an ASDU to a ready slave may be dispatched ahead of an
   * earlier-queued ASDU whose target is still being brought up or is back-pressured, so a single
   * blocked slave does not stall commands to the rest of the bus. An ASDU whose common address maps
   * to no usable secondary — an unconfigured address, or one degraded to {@link LinkState#ERROR} —
   * is answered locally with a {@link Cause#UNKNOWN_COMMON_ADDRESS} negative confirmation rather
   * than being silently dropped, so the caller learns of the unreachable station promptly instead
   * of by timeout.
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
      // Enforce the configured queue bound (0 == unbounded). BLOCK is never honored by parking here
      // (that would block the caller under the lock); a full BLOCK queue drops the newest as a
      // last-resort guard, the publisher having been expected to await capacity first.
      if (maxOutboundQueue > 0 && commandQueue.size() >= maxOutboundQueue) {
        switch (queuePolicy) {
          case DROP_OLDEST -> {
            commandQueue.pollFirst();
            commandQueue.addLast(asdu);
          }
          case DROP_NEWEST, BLOCK -> {
            // Drop the newly offered ASDU; keep the already accepted history.
          }
        }
      } else {
        commandQueue.addLast(asdu);
      }
      pump();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Awaits free capacity in the outbound command queue, off the engine's hot path, for the {@link
   * OutboundQueuePolicy#BLOCK} policy.
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
      while (!closed && commandQueue.size() >= maxOutboundQueue) {
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
   * Returns the number of ASDUs currently waiting in the outbound command queue (not yet handed to
   * the transport as a user-data frame).
   *
   * @return the pending command-queue depth.
   */
  @Override
  public int pendingSendCount() {
    lock.lock();
    try {
      return commandQueue.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Closes the master.
   *
   * <p>Cancels all timers and marks the session closed. This method is idempotent and does not
   * invoke {@link Session.Events#onClosed(Throwable)}: the unbalanced master never self-closes the
   * session on a protocol timeout (a dead slave is degraded to {@link LinkState#ERROR} instead), so
   * {@code close()} is reserved for the binding's reaction to a transport loss.
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
    } finally {
      lock.unlock();
    }
  }

  // --- Bus loop (lock held) -------------------------------------------------------------------

  /**
   * Advances the single-transaction bus loop, sending the next eligible frame while the bus is
   * idle.
   *
   * <p>Acts only while data transfer is started and the bus is free ({@code pending == null}). The
   * priority order is: (1) deliver the first command whose target slave can accept it now, scanning
   * past a leading command for a not-yet-ready slave (still being brought up, or
   * DFC-back-pressured) so it does not starve commands to other ready slaves (rejecting a command
   * for an unconfigured or failed slave with a synthesized negative confirmation rather than
   * silently dropping it, sending broadcasts immediately); (2) on a due poll tick, request class-2
   * data from the next available slave; (3) bring up the next not-yet-reset slave, round robin
   * across the configured secondaries.
   *
   * <p>Bring-up is the <em>lowest</em> priority on purpose: servicing a slave that is already
   * available must never be starved by bringing up a not-yet-available one. Together with the
   * round-robin bring-up cursor and the bus yield in {@link #onRequestStatusTimeout(Pending)}, this
   * keeps a single unresponsive slave from monopolizing the shared bus for its whole retransmit
   * budget while polls and commands to healthy slaves wait.
   */
  private void pump() {
    while (!closed && started && pending == null) {
      // Priority 1: deliver the first command whose target slave can accept it now. Scanning past a
      // leading command for a not-yet-ready slave — one still being brought up (UNRESET) or
      // available but DFC-back-pressured — keeps a single blocked slave from starving commands to
      // other ready slaves (cross-slave head-of-line blocking). Queue order is otherwise preserved,
      // so two commands for the same slave keep their submission order (deliverability is constant
      // within a scan, so the earliest command for any slave is always reached first).
      Asdu command = removeFirstDeliverableCommand();
      if (command != null) {
        int target = commonAddressOf(command);
        if (target == broadcastAddress) {
          sendBroadcast(command);
          continue; // FC4 expects no reply; the bus is free again.
        }
        SlaveState slave = slaves.get(target);
        if (slave == null || slave.linkState == LinkState.ERROR) {
          LOGGER.debug(
              "rejecting command for {} slave (CA={}) with a negative confirmation",
              slave == null ? "unconfigured" : "failed",
              target);
          rejectUndeliverable(command);
          continue; // the bus is still free; the loop guard re-checks closed/started
        }
        // The target is available and not back-pressured: send it as send/confirm user data.
        if (sendUserData(target, command)) {
          return; // a user-data transaction is now outstanding; the bus is busy
        }
        // Framing the user data failed (e.g. an oversized ASDU): the command was rejected locally
        // and the bus is still free, so keep scanning for other work rather than stalling.
        continue;
      }

      // Priority 2: cadence-driven class-2 poll of the next available slave.
      if (pollPending) {
        pollPending = false;
        Integer slave = nextSlaveToPoll();
        if (slave != null) {
          sendPollClass2(slave);
          return;
        }
        // No available slave to poll this tick; fall through to bring-up.
      }

      // Priority 3: bring up the next not-yet-reset slave (round robin across the secondaries).
      Integer unreset = nextSlaveToBringUp();
      if (unreset != null) {
        sendRequestStatus(unreset);
        return;
      }

      return; // nothing to do until the next ack or poll tick
    }
  }

  /**
   * Removes and returns the first queued command that can be acted on right now, scanning past any
   * leading commands whose target slave cannot currently accept user data — one still being brought
   * up (UNRESET) or DFC-back-pressured — so a single blocked slave does not starve commands to
   * other ready slaves. Queue order is otherwise preserved: because a target's deliverability does
   * not change during a single scan, the earliest queued command for any given slave is always
   * reached before its later commands, so two commands for the same slave keep their submission
   * order.
   *
   * @return the first deliverable command, removed from the queue, or {@code null} if none can be
   *     acted on now.
   */
  private @Nullable Asdu removeFirstDeliverableCommand() {
    Iterator<Asdu> it = commandQueue.iterator();
    while (it.hasNext()) {
      Asdu command = it.next();
      if (commandDeliverableNow(commonAddressOf(command))) {
        it.remove();
        queueDrained.signalAll();
        return command;
      }
    }
    return null;
  }

  /**
   * Returns the next not-yet-reset slave to bring up, advancing the bring-up cursor so consecutive
   * bring-up turns rotate across the configured secondaries.
   *
   * <p>Round-robin (rather than always the lowest-addressed slave) is what lets a healthy slave be
   * brought up even when an earlier-listed slave is unresponsive: when {@link
   * #onRequestStatusTimeout(Pending)} yields the bus after a dead slave's probe times out, the next
   * bring-up turn lands on a different slave instead of immediately re-probing the dead one.
   *
   * @return the link address of the next slave awaiting bring-up, or {@code null} if every
   *     configured slave is already available or has been degraded to error.
   */
  private @Nullable Integer nextSlaveToBringUp() {
    int n = slaveAddresses.size();
    for (int i = 0; i < n; i++) {
      int index = (bringUpCursor + i) % n;
      int address = slaveAddresses.get(index);
      SlaveState slave = slaves.get(address);
      if (slave != null && slave.linkState == LinkState.UNRESET) {
        bringUpCursor = (index + 1) % n;
        return address;
      }
    }
    return null;
  }

  /**
   * Reports whether the bus has work to do other than (re)probing the given not-yet-available
   * slave: another slave to bring up, a due class-2 poll of an available slave, or a queued command
   * that can be delivered or locally answered now. Used by {@link #onRequestStatusTimeout(Pending)}
   * to decide whether releasing the bus would actually let useful work proceed.
   *
   * @param excludeSlave the link address of the slave whose bring-up just timed out.
   * @return {@code true} if some other bus transaction could be serviced now.
   */
  private boolean hasServiceableWorkBesidesBringUp(int excludeSlave) {
    for (int address : slaveAddresses) {
      if (address == excludeSlave) {
        continue;
      }
      SlaveState slave = slaves.get(address);
      if (slave != null && slave.linkState == LinkState.UNRESET) {
        return true;
      }
    }
    if (pollPending && hasAvailableSlave()) {
      return true;
    }
    return hasDeliverableCommand();
  }

  private boolean hasAvailableSlave() {
    for (int address : slaveAddresses) {
      SlaveState slave = slaves.get(address);
      if (slave != null && slave.linkState == LinkState.AVAILABLE) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether any queued command could be acted on right now, scanning the whole command
   * queue rather than only its head so a leading command for a not-yet-ready slave does not mask a
   * deliverable command behind it — the same cross-slave fairness {@link #pump()} applies.
   *
   * @return {@code true} if {@link #pump()} would act on some queued command this turn.
   */
  private boolean hasDeliverableCommand() {
    for (Asdu command : commandQueue) {
      if (commandDeliverableNow(commonAddressOf(command))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether a command for the given target common address could be acted on right now —
   * delivered as user data to an available, non-back-pressured slave, sent as a broadcast, or
   * answered locally with a negative confirmation for an unconfigured or failed target. A command
   * for a slave still being brought up (UNRESET) or back-pressured (DFC) is not yet actionable.
   *
   * @param target the command's target common address.
   * @return {@code true} if {@link #pump()} would act on a command for this target this turn.
   */
  private boolean commandDeliverableNow(int target) {
    if (target == broadcastAddress) {
      return true;
    }
    SlaveState slave = slaves.get(target);
    if (slave == null || slave.linkState == LinkState.ERROR) {
      return true; // undeliverable: pump answers it with a negative confirmation
    }
    return slave.linkState == LinkState.AVAILABLE && !slave.dfc;
  }

  private @Nullable Integer nextSlaveToPoll() {
    int n = slaveAddresses.size();
    for (int i = 0; i < n; i++) {
      int index = (pollCursor + i) % n;
      int address = slaveAddresses.get(index);
      SlaveState slave = slaves.get(address);
      // DFC suppresses user data, not polling: a back-pressured slave is still polled so its data
      // drains and its DFC bit is re-read.
      if (slave != null && slave.linkState == LinkState.AVAILABLE) {
        pollCursor = (index + 1) % n;
        return address;
      }
    }
    return null;
  }

  // --- Inbound response handling (lock held) --------------------------------------------------

  /**
   * Reports whether an inbound frame is a secondary (response) frame the master should process,
   * logging and rejecting an unexpected primary frame.
   *
   * <p>A secondary station never initiates, so a primary frame received by the master is a protocol
   * violation that is logged and ignored.
   *
   * @param control the inbound frame's control field.
   * @return {@code true} if the frame is a secondary response to process, {@code false} if it is a
   *     primary frame to ignore.
   */
  private boolean acceptSecondary(LinkControlField control) {
    if (control.prm()) {
      LOGGER.debug(
          "ignoring unexpected primary frame from a secondary station (FC{})",
          control.functionCode());
      return false;
    }
    return true;
  }

  /**
   * Matches a secondary response (or a single-character acknowledgement) against the outstanding
   * transaction and advances the bus.
   *
   * @param fc the secondary function code ({@link #FC_ACK} for a single-character acknowledgement).
   * @param acd the access-demand bit.
   * @param dfc the data-flow-control bit.
   * @param address the responding station's link address, or {@code -1} for a single-character
   *     frame.
   * @param singleChar whether the frame was the {@code 0xE5} single-character acknowledgement.
   * @param asdu the carried ASDU for a user-data response, otherwise {@code null}.
   */
  private void handleSecondaryResponse(
      int fc, boolean acd, boolean dfc, int address, boolean singleChar, @Nullable Asdu asdu) {
    Pending p = pending;
    if (p == null) {
      LOGGER.debug("ignoring secondary frame (FC{}) with no outstanding transaction", fc);
      return;
    }
    if (!singleChar && address != p.slaveAddress()) {
      LOGGER.debug(
          "ignoring response from slave {} while awaiting slave {}", address, p.slaveAddress());
      return;
    }
    SlaveState slave = slaves.get(p.slaveAddress());
    switch (p.kind()) {
      case RESET_STATUS -> {
        if (fc == FC_STATUS_OF_LINK) {
          cancelConfirmTimer();
          if (slave != null) {
            slave.dfc = dfc;
            slave.bringUpRetries = 0; // the slave answered: the FC9 retransmission budget resets
          }
          sendReset(p.slaveAddress()); // proceed to the reset-of-remote-link, same slave
        } else {
          LOGGER.debug(
              "unexpected response FC{} to request-status-of-link from slave {}",
              fc,
              p.slaveAddress());
        }
      }
      case RESET -> {
        if (fc == FC_ACK) {
          cancelConfirmTimer();
          if (slave != null) {
            slave.linkState = LinkState.AVAILABLE;
            slave.nextFcb = true; // fresh FCB sequence after a reset
            slave.dfc = dfc;
          }
          pending = null;
          pump();
        } else {
          LOGGER.debug(
              "unexpected response FC{} to reset-of-remote-link from slave {}",
              fc,
              p.slaveAddress());
        }
      }
      case POLL_CLASS2, POLL_CLASS1 -> handlePollResponse(p, slave, fc, acd, dfc, singleChar, asdu);
      case USER_DATA -> {
        if (fc == FC_ACK) {
          cancelConfirmTimer();
          if (slave != null) {
            slave.nextFcb = !slave.nextFcb;
            slave.dfc = dfc;
          }
          pending = null;
          // The ASDU was already removed from the command queue when it was sent.
          if (tryEscalateAcd(p.slaveAddress(), acd, 0)) {
            return; // a class-1 drain is now outstanding; the bus is busy again
          }
          pump();
        } else if (fc == FC_NACK) {
          LOGGER.debug(
              "slave {} did not accept user data (FC1); retransmitting on confirm timeout",
              p.slaveAddress());
        } else {
          LOGGER.debug("unexpected response FC{} to user data from slave {}", fc, p.slaveAddress());
        }
      }
    }
  }

  private void handlePollResponse(
      Pending p,
      @Nullable SlaveState slave,
      int fc,
      boolean acd,
      boolean dfc,
      boolean singleChar,
      @Nullable Asdu asdu) {
    if (fc == FC_RESPOND_USER_DATA) {
      cancelConfirmTimer();
      if (slave != null) {
        slave.nextFcb = !slave.nextFcb;
        slave.dfc = dfc;
      }
      pending = null;
      if (asdu != null) {
        events.onAsdu(asdu);
        if (closed) {
          return;
        }
      }
      if (tryEscalateAcd(p.slaveAddress(), acd, p.acdDrain())) {
        return; // a class-1 drain is now outstanding; the bus is busy again
      }
      pump();
    } else if (singleChar || fc == FC_RESPOND_NO_DATA) {
      // No data available: the FCV=1 transaction still completed, so the FCB toggles.
      cancelConfirmTimer();
      if (slave != null) {
        slave.nextFcb = !slave.nextFcb;
        slave.dfc = dfc;
      }
      pending = null;
      if (tryEscalateAcd(p.slaveAddress(), acd, p.acdDrain())) {
        return; // a class-1 drain is now outstanding; the bus is busy again
      }
      pump();
    } else if (fc == FC_NACK) {
      LOGGER.debug("slave {} link busy (FC1) on class poll; retransmitting", p.slaveAddress());
    } else {
      LOGGER.debug("unexpected response FC{} to class poll from slave {}", fc, p.slaveAddress());
    }
  }

  /**
   * Escalates to a request-class-1 drain when the slave asserted access demand, bounded by {@link
   * #MAX_ACD_DRAIN}.
   *
   * @param slaveAddress the slave to drain.
   * @param acd the access-demand bit from the response just processed.
   * @param currentDrain the number of class-1 drains already performed in this escalation chain.
   * @return {@code true} if a request-class-1 transaction was issued (the bus is now busy), {@code
   *     false} if no escalation was warranted (the caller should resume the bus).
   */
  private boolean tryEscalateAcd(int slaveAddress, boolean acd, int currentDrain) {
    if (!acd) {
      return false;
    }
    // A class-1 drain is a fresh bus transaction; never start one after the poller has stopped.
    if (!started) {
      return false;
    }
    SlaveState slave = slaves.get(slaveAddress);
    if (slave == null || slave.linkState != LinkState.AVAILABLE) {
      return false;
    }
    int nextDrain = currentDrain + 1;
    if (nextDrain > MAX_ACD_DRAIN) {
      LOGGER.debug(
          "ACD drain bound {} reached for slave {}; deferring remaining class-1 to the next poll",
          MAX_ACD_DRAIN,
          slaveAddress);
      return false;
    }
    sendPollClass1(slaveAddress, nextDrain);
    return true;
  }

  // --- Outbound transactions (lock held) ------------------------------------------------------

  private void sendRequestStatus(int slaveAddress) {
    setPending(new Pending(slaveAddress, Kind.RESET_STATUS, null, false, 0));
    output.send(fixedPrimary(slaveAddress, false, false, FC_REQUEST_STATUS_OF_LINK));
    if (closed) {
      return;
    }
    armConfirmTimer(confirmTimeoutMillis);
  }

  private void sendReset(int slaveAddress) {
    setPending(new Pending(slaveAddress, Kind.RESET, null, false, 0));
    output.send(fixedPrimary(slaveAddress, false, false, FC_RESET_REMOTE_LINK));
    if (closed) {
      return;
    }
    armConfirmTimer(confirmTimeoutMillis);
  }

  private void sendPollClass2(int slaveAddress) {
    boolean fcb = fcbFor(slaveAddress);
    setPending(new Pending(slaveAddress, Kind.POLL_CLASS2, null, fcb, 0));
    output.send(fixedPrimary(slaveAddress, fcb, true, FC_REQUEST_CLASS_2_DATA));
    if (closed) {
      return;
    }
    armConfirmTimer(confirmTimeoutMillis);
  }

  private void sendPollClass1(int slaveAddress, int drain) {
    boolean fcb = fcbFor(slaveAddress);
    setPending(new Pending(slaveAddress, Kind.POLL_CLASS1, null, fcb, drain));
    output.send(fixedPrimary(slaveAddress, fcb, true, FC_REQUEST_CLASS_1_DATA));
    if (closed) {
      return;
    }
    armConfirmTimer(confirmTimeoutMillis);
  }

  /**
   * Sends {@code asdu} to {@code slaveAddress} as a send/confirm user-data frame (FC3, FCV=1),
   * opening the single-transaction window and arming the confirm timer.
   *
   * <p>Framing the user data can fail synchronously, before the frame ever reaches the bus: the
   * FT1.2 framer behind {@link Ft12LinkLayer.Output#send(Ft12Frame)} rejects user data longer than
   * {@code 255} octets, so publishing one oversized ASDU makes {@code output.send} throw. This
   * method is therefore exception-safe. On such a throw it <em>rolls back</em> the pending
   * transaction it just opened — otherwise this slave's stop-and-wait path would be wedged
   * permanently, left {@link Pending pending} with no confirm timer ever armed to clear it — and
   * answers the offending command locally with a negative confirmation so the façade fails the
   * corresponding operation promptly instead of by a generic timeout. The bus is then reported free
   * again so the caller can continue servicing other work.
   *
   * @param slaveAddress the target secondary's link address.
   * @param asdu the command ASDU to transmit.
   * @return {@code true} if a user-data transaction is now outstanding (the bus is busy), {@code
   *     false} if framing failed and the command was rejected locally (the bus is still free).
   */
  private boolean sendUserData(int slaveAddress, Asdu asdu) {
    boolean fcb = fcbFor(slaveAddress);
    setPending(new Pending(slaveAddress, Kind.USER_DATA, asdu, fcb, 0));
    try {
      output.send(
          new Ft12Frame.Variable(
              LinkControlField.primary(false, fcb, true, FC_USER_DATA), slaveAddress, asdu));
    } catch (RuntimeException e) {
      // Framing failed before anything reached the bus (e.g. an ASDU too large for a single FT1.2
      // variable frame). Roll back the just-opened transaction so the bus is not left wedged with a
      // pending transaction that has no armed confirm timer; the FCB was only read, not toggled, so
      // it needs no rollback. Then fail the command locally so the façade learns of it promptly.
      pending = null;
      retryCount = 0;
      LOGGER.debug(
          "framing user data for slave {} failed; rejecting the command locally", slaveAddress, e);
      // There is no standard IEC cause for an unframeable (over-length) command, so echo it back as
      // a negative confirmation with the closest generic rejection cause; the precise framing error
      // is carried by the log above.
      deliverNegativeConfirmation(asdu, Cause.UNKNOWN_CAUSE);
      return false;
    }
    if (closed) {
      return true;
    }
    armConfirmTimer(confirmTimeoutMillis);
    return true;
  }

  private void sendBroadcast(Asdu asdu) {
    // FC4 send/no-reply to all stations: FCV=0, no confirmation, no pending transaction.
    output.send(
        new Ft12Frame.Variable(
            LinkControlField.primary(false, false, false, FC_USER_DATA_NO_REPLY),
            broadcastAddress,
            asdu));
  }

  private void retransmitPending(Pending p) {
    // Verbatim retransmission: same function code and same FCB, no new pending or FCB toggle.
    switch (p.kind()) {
      case RESET_STATUS ->
          output.send(fixedPrimary(p.slaveAddress(), false, false, FC_REQUEST_STATUS_OF_LINK));
      case RESET -> output.send(fixedPrimary(p.slaveAddress(), false, false, FC_RESET_REMOTE_LINK));
      case POLL_CLASS2 ->
          output.send(fixedPrimary(p.slaveAddress(), p.fcb(), true, FC_REQUEST_CLASS_2_DATA));
      case POLL_CLASS1 ->
          output.send(fixedPrimary(p.slaveAddress(), p.fcb(), true, FC_REQUEST_CLASS_1_DATA));
      case USER_DATA ->
          output.send(
              new Ft12Frame.Variable(
                  LinkControlField.primary(false, p.fcb(), true, FC_USER_DATA),
                  p.slaveAddress(),
                  Objects.requireNonNull(p.asdu(), "asdu")));
    }
  }

  private boolean fcbFor(int slaveAddress) {
    SlaveState slave = slaves.get(slaveAddress);
    return slave != null && slave.nextFcb;
  }

  private void setPending(Pending p) {
    this.pending = p;
    this.retryCount = 0;
  }

  private Ft12Frame fixedPrimary(int address, boolean fcb, boolean fcv, int functionCode) {
    // Unbalanced primary frames carry RES (bit 7 = 0), so dir is always false.
    return new Ft12Frame.FixedLength(
        LinkControlField.primary(false, fcb, fcv, functionCode), address);
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

  private void armPollTimer() {
    cancelPollTimer();
    int generation = ++pollGeneration;
    pollFuture = schedule(() -> onPollTick(generation), pollIntervalMillis);
  }

  private void cancelPollTimer() {
    pollGeneration++;
    if (pollFuture != null) {
      pollFuture.cancel(false);
      pollFuture = null;
    }
  }

  private void cancelAllTimers() {
    cancelConfirmTimer();
    cancelPollTimer();
  }

  private void onConfirmExpired(int generation) {
    lock.lock();
    try {
      // Ignore a stale task: closed, or the timer was re-armed/cancelled after this task
      // dispatched.
      if (closed || generation != confirmGeneration) {
        return;
      }
      confirmFuture = null;
      Pending p = pending;
      if (p == null) {
        return; // confirmed synchronously after this timer was armed; nothing to do
      }
      if (p.kind() == Kind.RESET_STATUS) {
        // Bring-up of a not-yet-available slave gets fair, bus-yielding retransmission so one
        // unresponsive slave does not monopolize the shared bus for its whole retransmit budget.
        onRequestStatusTimeout(p);
        return;
      }
      if (retryCount < maxRetries) {
        retryCount++;
        retransmitPending(p);
        if (closed) {
          return;
        }
        armConfirmTimer(repeatTimeoutMillis);
      } else {
        // Degrade only THIS slave; the bus and the session stay open (one dead slave must not kill
        // the bus). The session closes only on a transport loss, via the binding calling close().
        if (p.kind() == Kind.USER_DATA) {
          LOGGER.debug(
              "dropping undeliverable user data for slave {} after {} retransmissions",
              p.slaveAddress(),
              maxRetries);
        }
        markSlaveError(p.slaveAddress());
        pending = null;
        pump();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Handles a request-status-of-link (FC9) bring-up timeout for a not-yet-available slave, fairly,
   * so a single unresponsive secondary cannot monopolize the shared bus.
   *
   * <p>Bring-up retransmissions are counted <em>per slave</em> (on {@link
   * SlaveState#bringUpRetries}) rather than on the shared {@link #retryCount}, so the offending
   * secondary is still degraded to {@link LinkState#ERROR} once it exceeds {@link #maxRetries}
   * unanswered probes. To keep that slave from holding the single-transaction bus for its entire
   * retransmit budget — which would stall polls and commands to slaves that are already available —
   * the bus is <em>released</em> between probes whenever {@linkplain
   * #hasServiceableWorkBesidesBringUp(int) other work} could run (another slave to bring up, a due
   * poll, or a deliverable command); that work is serviced first and this slave is re-probed on a
   * later bus turn. When nothing else needs the bus the frame is retransmitted in place on the
   * repeat cadence, leaving the lone-slave / idle-bus timing unchanged.
   *
   * @param p the outstanding request-status-of-link transaction that just timed out.
   */
  private void onRequestStatusTimeout(Pending p) {
    SlaveState slave = slaves.get(p.slaveAddress());
    int attempts = (slave == null) ? maxRetries + 1 : ++slave.bringUpRetries;
    if (attempts > maxRetries) {
      // Degrade only THIS slave; the bus and the session stay open (one dead slave must not kill
      // the
      // bus). The session closes only on a transport loss, via the binding calling close().
      markSlaveError(p.slaveAddress());
      pending = null;
      pump();
      return;
    }
    if (hasServiceableWorkBesidesBringUp(p.slaveAddress())) {
      // Release the bus: service the other work now and re-probe this slave on a later turn (its
      // accumulated bringUpRetries still drive it to ERROR). This is the fair, interleaved path.
      pending = null;
      pump();
    } else {
      // Nothing else needs the bus: retransmit in place on the repeat cadence (unchanged behavior).
      retransmitPending(p);
      if (closed) {
        return;
      }
      armConfirmTimer(repeatTimeoutMillis);
    }
  }

  private void onPollTick(int generation) {
    lock.lock();
    try {
      if (closed || generation != pollGeneration) {
        return;
      }
      pollFuture = null;
      if (!started) {
        return; // the poller was stopped
      }
      armPollTimer(); // keep the cadence ticking
      pollPending = true;
      pump();
    } finally {
      lock.unlock();
    }
  }

  private ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
    return scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
  }

  // --- Misc (lock held) -----------------------------------------------------------------------

  private void markSlaveError(int slaveAddress) {
    SlaveState slave = slaves.get(slaveAddress);
    if (slave != null) {
      slave.linkState = LinkState.ERROR;
    }
    LOGGER.debug("slave {} marked ERROR after {} retransmissions", slaveAddress, maxRetries);
  }

  private void resetState() {
    started = false;
    pending = null;
    retryCount = 0;
    pollPending = false;
    pollCursor = 0;
    bringUpCursor = 0;
    commandQueue.clear();
    slaves.clear();
    for (int address : slaveAddresses) {
      slaves.put(address, new SlaveState());
    }
  }

  private static int commonAddressOf(Asdu asdu) {
    return asdu.commonAddress().value().intValue();
  }

  /**
   * Locally answers a command that can never reach the bus with the negative confirmation a
   * controlled station would itself return, so the high-level facade fails the corresponding
   * operation promptly instead of blocking until a generic timeout.
   *
   * <p>The unbalanced master maps each command's common address onto the link address of a
   * configured secondary (the frozen façade mapping). A command whose common address matches no
   * secondary the master can put on the bus — the address is unconfigured, or the secondary has
   * been degraded to {@link LinkState#ERROR} after exhausting its retries — is undeliverable.
   * Rather than discarding it with only a log line, the master delivers the same ASDU echoed back
   * with the positive/negative (P/N) bit set and cause {@link Cause#UNKNOWN_COMMON_ADDRESS},
   * exactly the reply a real secondary gives for a command addressed to a common address it does
   * not serve. The facade correlates this negative confirmation to the waiting
   * command/interrogation and surfaces a clear, prompt rejection. The offending slave's {@link
   * LinkState} is left unchanged.
   *
   * @param command the undeliverable command ASDU.
   */
  private void rejectUndeliverable(Asdu command) {
    deliverNegativeConfirmation(command, Cause.UNKNOWN_COMMON_ADDRESS);
  }

  /**
   * Answers {@code command} locally with the negative confirmation a controlled station would
   * return — the same ASDU echoed back with the positive/negative (P/N) bit set and the supplied
   * {@code cause} — and delivers it through {@link Session.Events#onAsdu(Asdu)} so the high-level
   * façade correlates it to the waiting operation and fails that operation promptly rather than
   * waiting for a generic timeout.
   *
   * @param command the command ASDU to echo back as a negative confirmation.
   * @param cause the cause of transmission to stamp on the rejection.
   */
  private void deliverNegativeConfirmation(Asdu command, Cause cause) {
    Asdu rejection =
        new Asdu(
            command.type(),
            command.sequence(),
            cause,
            true, // P/N = 1: a negative confirmation
            command.test(),
            command.originatorAddress(),
            command.commonAddress(),
            command.objects());
    events.onAsdu(rejection);
  }

  private static CompletableFuture<Void> failedFuture(Throwable cause) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    future.completeExceptionally(cause);
    return future;
  }
}
