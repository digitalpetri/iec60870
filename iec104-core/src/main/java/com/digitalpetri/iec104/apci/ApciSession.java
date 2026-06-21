package com.digitalpetri.iec104.apci;

import com.digitalpetri.iec104.ApciSettings;
import com.digitalpetri.iec104.ConnectionClosedException;
import com.digitalpetri.iec104.ProtocolTimeoutException;
import com.digitalpetri.iec104.SequenceNumberException;
import com.digitalpetri.iec104.asdu.Asdu;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The symmetric IEC 60870-5-104 APCI link state machine shared by the client and the server.
 *
 * <p>An {@code ApciSession} owns the numbered-frame flow control for one transport connection: the
 * send and receive state variables V(S) and V(R), the {@code k}/{@code w} sliding window, and the
 * {@code t1}/{@code t2}/{@code t3} timers. It is transport-agnostic — outbound APDUs are handed to
 * the supplied {@link Output}, inbound APDUs are fed in via {@link #onApdu(Apdu)}, and delivered
 * application ASDUs plus lifecycle events are reported through the supplied {@link Events}. The
 * session never touches a socket or a {@code ByteBuf} directly.
 *
 * <p>The session is constructed in a {@link Role} that selects the small handshake differences: a
 * {@link Role#CLIENT} initiates STARTDT/STOPDT and waits for the confirmation, while a {@link
 * Role#SERVER} replies to those activations and withholds queued I-frames until data transfer has
 * been started.
 *
 * <p><b>Lifecycle.</b> Call {@link #onConnected()} once the transport is up to reset the sequence
 * numbers and arm the idle timer; then feed every inbound APDU to {@link #onApdu(Apdu)} and send
 * application data with {@link #sendAsdu(Asdu)}. Call {@link #close()} when the transport goes
 * away; it is idempotent.
 *
 * <p><b>Threading.</b> All mutable state is guarded by a single internal lock, and the timer
 * callbacks scheduled on the injected {@link ScheduledExecutorService} acquire that same lock, so a
 * caller may invoke any method from any thread. The {@link Output} and {@link Events} callbacks are
 * always invoked while the lock is held; they must not call back into the session and should not
 * block.
 *
 * <p>Typical wiring:
 *
 * <pre>{@code
 * ApciSession session = new ApciSession(
 *     ApciSession.Role.CLIENT, ApciSettings.defaults(), scheduler,
 *     apdu -> transport.send(apdu),
 *     new ApciSession.Events() {
 *       public void onAsdu(Asdu asdu) { application.deliver(asdu); }
 *       public void onDataTransferStateChanged(boolean started) { ... }
 *       public void onClosed(Throwable cause) { transport.close(); }
 *     });
 *
 * session.onConnected();
 * session.startDataTransfer().toCompletableFuture().join();
 * session.sendAsdu(asdu);
 * }</pre>
 */
public final class ApciSession {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApciSession.class);

  /** The wraparound modulus of the 15-bit send and receive sequence numbers. */
  private static final int SEQUENCE_MODULUS = 32768;

  /** The role a session plays in the STARTDT/STOPDT handshake. */
  public enum Role {

    /** The controlling station: initiates STARTDT/STOPDT and awaits the confirmation. */
    CLIENT,

    /** The controlled station: replies to STARTDT/STOPDT activations and gates monitor data. */
    SERVER
  }

  /** Sink for APDUs the session wants to transmit on the underlying transport. */
  public interface Output {

    /**
     * Transmits an APDU produced by the session.
     *
     * <p>Invoked while the session lock is held; implementations must not block or re-enter the
     * session.
     *
     * @param apdu the APDU to send.
     */
    void send(Apdu apdu);
  }

  /** Callbacks for application ASDUs and lifecycle transitions produced by the session. */
  public interface Events {

    /**
     * Delivers an application ASDU received in an I-format APDU.
     *
     * <p>Invoked while the session lock is held; implementations must not block or re-enter the
     * session.
     *
     * @param asdu the received ASDU.
     */
    void onAsdu(Asdu asdu);

    /**
     * Reports a change in the data-transfer (STARTDT/STOPDT) state.
     *
     * <p>Invoked while the session lock is held; implementations must not block or re-enter the
     * session.
     *
     * @param started {@code true} when data transfer has started, {@code false} when it has
     *     stopped.
     */
    void onDataTransferStateChanged(boolean started);

    /**
     * Reports that the session has closed itself because of a protocol error or timeout.
     *
     * <p>Invoked at most once while the session lock is held. The transport should be torn down in
     * response; the session has already cancelled its timers.
     *
     * @param cause the reason for closure, or {@code null} for an orderly close.
     */
    void onClosed(@Nullable Throwable cause);
  }

  private final ReentrantLock lock = new ReentrantLock();

  private final Role role;
  private final ScheduledExecutorService scheduler;
  private final Output output;
  private final Events events;

  private final long t1Millis;
  private final long t2Millis;
  private final long t3Millis;
  private final int k;
  private final int w;

  // Send-side state.
  private int sendSequenceNumber; // V(S)
  private int ackSequenceNumber; // lowest unacknowledged send sequence number
  private final ArrayDeque<Asdu> sendQueue = new ArrayDeque<>();

  // Receive-side state.
  private int receiveSequenceNumber; // V(R)
  private int unackedReceivedCount; // I-frames received since the last S/I acknowledgement

  // Data-transfer (STARTDT/STOPDT) state.
  private boolean dataTransferStarted;
  private @Nullable CompletableFuture<Void> pendingStart;
  private @Nullable CompletableFuture<Void> pendingStop;

  // Timer handles.
  private @Nullable ScheduledFuture<?> t1Future;
  private @Nullable ScheduledFuture<?> t2Future;
  private @Nullable ScheduledFuture<?> t3Future;
  private boolean testFrameOutstanding;

  private boolean closed;

  /**
   * Creates an APCI session.
   *
   * @param role the role this session plays in the STARTDT/STOPDT handshake.
   * @param settings the APCI flow-control parameters ({@code k}, {@code w}, and the timers).
   * @param scheduler the executor used to schedule the {@code t1}/{@code t2}/{@code t3} timers;
   *     callbacks run under the session lock.
   * @param output the sink for outbound APDUs.
   * @param events the callbacks for delivered ASDUs and lifecycle transitions.
   */
  public ApciSession(
      Role role,
      ApciSettings settings,
      ScheduledExecutorService scheduler,
      Output output,
      Events events) {

    this.role = Objects.requireNonNull(role, "role");
    Objects.requireNonNull(settings, "settings");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.output = Objects.requireNonNull(output, "output");
    this.events = Objects.requireNonNull(events, "events");

    this.t1Millis = settings.t1().toMillis();
    this.t2Millis = settings.t2().toMillis();
    this.t3Millis = settings.t3().toMillis();
    this.k = settings.k().intValue();
    this.w = settings.w().intValue();
  }

  /**
   * Computes the forward distance from {@code from} to {@code to} modulo {@code 32768}.
   *
   * <p>The result is the number of increments of a 15-bit sequence number needed to advance from
   * {@code from} to {@code to}, in the range {@code 0..32767}, correctly accounting for wraparound
   * at {@code 32768}.
   *
   * @param from the starting sequence number, in {@code 0..32767}.
   * @param to the ending sequence number, in {@code 0..32767}.
   * @return the forward distance modulo {@code 32768}.
   */
  public static int sequenceDistance(int from, int to) {
    return Math.floorMod(to - from, SEQUENCE_MODULUS);
  }

  /**
   * Resets the session for a freshly established transport connection.
   *
   * <p>Both sequence state variables are set to zero, all pending state is cleared, data transfer
   * returns to the stopped default, and the {@code t3} idle timer is armed.
   */
  public void onConnected() {
    lock.lock();
    try {
      closed = false;
      sendSequenceNumber = 0;
      ackSequenceNumber = 0;
      receiveSequenceNumber = 0;
      unackedReceivedCount = 0;
      dataTransferStarted = false;
      testFrameOutstanding = false;
      sendQueue.clear();
      cancelAllTimers();
      armT3();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Handles a single inbound APDU according to its I, S, or U format and this session's role.
   *
   * <p>I-format APDUs whose send sequence number does not match the expected receive state close
   * the session with a {@link SequenceNumberException}. Acknowledgements that do not correspond to
   * outstanding sent frames likewise close the session. Otherwise the frame is processed, any
   * acknowledged sent frames are released (which may open the window and flush queued I-frames),
   * and the appropriate acknowledgement and idle timers are re-armed.
   *
   * @param apdu the inbound APDU.
   */
  public void onApdu(Apdu apdu) {
    Objects.requireNonNull(apdu, "apdu");
    lock.lock();
    try {
      if (closed) {
        return;
      }
      // Any received frame is activity: restart the idle timer.
      armT3();

      ControlField control = apdu.control();
      if (control instanceof ControlField.TypeI i) {
        onIFrame(i, Objects.requireNonNull(apdu.asdu()));
      } else if (control instanceof ControlField.TypeS s) {
        onSFrame(s);
      } else if (control instanceof ControlField.TypeU u) {
        onUFrame(u.function());
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Sends an application ASDU as an I-format APDU, honoring the {@code k} window.
   *
   * <p>If the number of outstanding unacknowledged I-frames has reached {@code k}, or — for a
   * {@link Role#SERVER} — data transfer has not been started, the ASDU is queued and transmitted
   * later when the window opens or data transfer starts. Queued ASDUs are sent in submission order.
   *
   * @param asdu the application ASDU to send.
   */
  public void sendAsdu(Asdu asdu) {
    Objects.requireNonNull(asdu, "asdu");
    lock.lock();
    try {
      if (closed) {
        return;
      }
      sendQueue.addLast(asdu);
      flushSendQueue();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Starts user-data transfer (CLIENT role only).
   *
   * <p>Sends a {@code STARTDT act} U-frame and returns a stage that completes when the matching
   * {@code STARTDT con} arrives. If the confirmation does not arrive before {@code t1} elapses the
   * session closes and the stage — together with the {@link Events#onClosed(Throwable)} callback —
   * completes exceptionally with a {@link ProtocolTimeoutException}.
   *
   * @return a stage that completes when data transfer has started, or completes exceptionally on
   *     timeout, session close, or misuse.
   * @throws IllegalStateException if called on a {@link Role#SERVER} session.
   */
  public CompletionStage<Void> startDataTransfer() {
    lock.lock();
    try {
      if (role != Role.CLIENT) {
        throw new IllegalStateException("startDataTransfer is only valid for a CLIENT session");
      }
      if (closed) {
        return failedFuture(new ConnectionClosedException("session is closed"));
      }
      if (dataTransferStarted) {
        return CompletableFuture.completedFuture(null);
      }
      if (pendingStart != null) {
        return pendingStart;
      }
      CompletableFuture<Void> future = new CompletableFuture<>();
      pendingStart = future;
      sendUFrame(UFunction.STARTDT_ACT);
      armT1();
      return future;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops user-data transfer (CLIENT role only).
   *
   * <p>Sends a {@code STOPDT act} U-frame and returns a stage that completes when the matching
   * {@code STOPDT con} arrives. If the confirmation does not arrive before {@code t1} elapses the
   * session closes and the stage completes exceptionally with a {@link ProtocolTimeoutException}.
   *
   * @return a stage that completes when data transfer has stopped, or completes exceptionally on
   *     timeout, session close, or misuse.
   * @throws IllegalStateException if called on a {@link Role#SERVER} session.
   */
  public CompletionStage<Void> stopDataTransfer() {
    lock.lock();
    try {
      if (role != Role.CLIENT) {
        throw new IllegalStateException("stopDataTransfer is only valid for a CLIENT session");
      }
      if (closed) {
        return failedFuture(new ConnectionClosedException("session is closed"));
      }
      if (!dataTransferStarted && pendingStart == null) {
        return CompletableFuture.completedFuture(null);
      }
      if (pendingStop != null) {
        return pendingStop;
      }
      CompletableFuture<Void> future = new CompletableFuture<>();
      pendingStop = future;
      sendUFrame(UFunction.STOPDT_ACT);
      armT1();
      return future;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Reports whether user-data transfer is currently started.
   *
   * @return {@code true} if STARTDT is in effect.
   */
  public boolean isDataTransferStarted() {
    lock.lock();
    try {
      return dataTransferStarted;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Closes the session.
   *
   * <p>Cancels all timers, fails any pending STARTDT/STOPDT future with a {@link
   * ConnectionClosedException}, and marks the session closed. This method is idempotent and does
   * not invoke {@link Events#onClosed(Throwable)} (that callback is reserved for self-initiated
   * closes triggered by protocol errors and timeouts).
   */
  public void close() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      cancelAllTimers();
      failPending(new ConnectionClosedException("session closed"));
    } finally {
      lock.unlock();
    }
  }

  // --- Inbound frame handling (lock held) -----------------------------------------------------

  private void onIFrame(ControlField.TypeI i, Asdu asdu) {
    int expected = receiveSequenceNumber;
    if (i.sendSequenceNumber() != expected) {
      closeWithError(
          new SequenceNumberException(
              "unexpected I-frame N(S): got " + i.sendSequenceNumber() + ", expected " + expected));
      return;
    }

    // Acknowledge any of our sent frames the peer confirmed via N(R).
    processReceiveSequenceNumber(i.receiveSequenceNumber());

    // Deliver and advance V(R).
    events.onAsdu(asdu);
    receiveSequenceNumber = increment(receiveSequenceNumber);
    unackedReceivedCount++;

    if (unackedReceivedCount >= w) {
      sendSupervisoryAck();
    } else {
      armT2();
    }
  }

  private void onSFrame(ControlField.TypeS s) {
    processReceiveSequenceNumber(s.receiveSequenceNumber());
  }

  private void onUFrame(UFunction function) {
    switch (function) {
      case TESTFR_ACT -> sendUFrame(UFunction.TESTFR_CON);
      case TESTFR_CON -> {
        testFrameOutstanding = false;
        cancelT1();
      }
      case STARTDT_ACT -> handleStartActivation();
      case STOPDT_ACT -> handleStopActivation();
      case STARTDT_CON -> handleStartConfirmation();
      case STOPDT_CON -> handleStopConfirmation();
    }
  }

  private void handleStartActivation() {
    if (role != Role.SERVER) {
      LOGGER.debug("ignoring STARTDT act received by a CLIENT session");
      return;
    }
    boolean changed = !dataTransferStarted;
    dataTransferStarted = true;
    sendUFrame(UFunction.STARTDT_CON);
    if (changed) {
      events.onDataTransferStateChanged(true);
      flushSendQueue();
    }
  }

  private void handleStopActivation() {
    if (role != Role.SERVER) {
      LOGGER.debug("ignoring STOPDT act received by a CLIENT session");
      return;
    }
    boolean changed = dataTransferStarted;
    dataTransferStarted = false;
    sendUFrame(UFunction.STOPDT_CON);
    if (changed) {
      events.onDataTransferStateChanged(false);
    }
  }

  private void handleStartConfirmation() {
    if (role != Role.CLIENT) {
      LOGGER.debug("ignoring STARTDT con received by a SERVER session");
      return;
    }
    boolean changed = !dataTransferStarted;
    dataTransferStarted = true;
    CompletableFuture<Void> future = pendingStart;
    pendingStart = null;
    cancelT1();
    if (changed) {
      events.onDataTransferStateChanged(true);
      flushSendQueue();
    }
    if (future != null) {
      // null is the only valid completion value for a CompletableFuture<Void>.
      //noinspection DataFlowIssue
      future.complete(null);
    }
  }

  private void handleStopConfirmation() {
    if (role != Role.CLIENT) {
      LOGGER.debug("ignoring STOPDT con received by a SERVER session");
      return;
    }
    boolean changed = dataTransferStarted;
    dataTransferStarted = false;
    CompletableFuture<Void> future = pendingStop;
    pendingStop = null;
    cancelT1();
    if (changed) {
      events.onDataTransferStateChanged(false);
    }
    if (future != null) {
      // null is the only valid completion value for a CompletableFuture<Void>.
      //noinspection DataFlowIssue
      future.complete(null);
    }
  }

  /**
   * Confirms sent I-frames acknowledged by a received N(R), updating the acknowledgement pointer
   * and re-arming or cancelling {@code t1} for the remaining outstanding frames.
   */
  private void processReceiveSequenceNumber(int receivedNr) {
    int outstanding = sequenceDistance(ackSequenceNumber, sendSequenceNumber);
    int acked = sequenceDistance(ackSequenceNumber, receivedNr);
    if (acked > outstanding) {
      closeWithError(
          new SequenceNumberException(
              "invalid acknowledgement N(R)="
                  + receivedNr
                  + " (outstanding "
                  + ackSequenceNumber
                  + ".."
                  + sendSequenceNumber
                  + ")"));
      return;
    }
    if (acked == 0) {
      return;
    }
    ackSequenceNumber = receivedNr;
    if (ackSequenceNumber == sendSequenceNumber) {
      // Nothing outstanding; only keep t1 running if a test frame is awaiting its confirmation.
      if (!testFrameOutstanding) {
        cancelT1();
      }
    } else {
      armT1();
    }
    flushSendQueue();
  }

  // --- Outbound helpers (lock held) -----------------------------------------------------------

  private void flushSendQueue() {
    if (role == Role.SERVER && !dataTransferStarted) {
      return;
    }
    while (!sendQueue.isEmpty() && sequenceDistance(ackSequenceNumber, sendSequenceNumber) < k) {
      Asdu asdu = sendQueue.removeFirst();
      ControlField.TypeI control =
          new ControlField.TypeI(sendSequenceNumber, receiveSequenceNumber);
      output.send(new Apdu(control, asdu));
      // Sending an I-frame piggybacks the acknowledgement of received frames.
      onReceiveAcknowledged();
      sendSequenceNumber = increment(sendSequenceNumber);
      armT1();
      armT3();
    }
  }

  private void sendSupervisoryAck() {
    output.send(new Apdu(new ControlField.TypeS(receiveSequenceNumber), null));
    onReceiveAcknowledged();
    armT3();
  }

  private void onReceiveAcknowledged() {
    unackedReceivedCount = 0;
    cancelT2();
  }

  private void sendUFrame(UFunction function) {
    output.send(new Apdu(new ControlField.TypeU(function), null));
    armT3();
  }

  // --- Timers (lock held) ---------------------------------------------------------------------

  private void armT1() {
    cancelT1();
    t1Future = schedule(this::onT1Expired, t1Millis);
  }

  private void cancelT1() {
    if (t1Future != null) {
      t1Future.cancel(false);
      t1Future = null;
    }
  }

  private void armT2() {
    if (t2Future != null) {
      return; // already pending; do not restart on every received frame
    }
    t2Future = schedule(this::onT2Expired, t2Millis);
  }

  private void cancelT2() {
    if (t2Future != null) {
      t2Future.cancel(false);
      t2Future = null;
    }
  }

  private void armT3() {
    cancelT3();
    t3Future = schedule(this::onT3Expired, t3Millis);
  }

  private void cancelT3() {
    if (t3Future != null) {
      t3Future.cancel(false);
      t3Future = null;
    }
  }

  private void cancelAllTimers() {
    cancelT1();
    cancelT2();
    cancelT3();
  }

  private void onT1Expired() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      t1Future = null;
      closeWithError(
          new ProtocolTimeoutException(
              "t1 elapsed awaiting acknowledgement of a sent I-frame or U-frame"));
    } finally {
      lock.unlock();
    }
  }

  private void onT2Expired() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      t2Future = null;
      if (unackedReceivedCount > 0) {
        sendSupervisoryAck();
      }
    } finally {
      lock.unlock();
    }
  }

  private void onT3Expired() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      t3Future = null;
      // Only probe an otherwise idle connection.
      if (!testFrameOutstanding) {
        testFrameOutstanding = true;
        sendUFrame(UFunction.TESTFR_ACT);
        armT1();
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
    failPending(cause);
    events.onClosed(cause);
  }

  private void failPending(Throwable cause) {
    CompletableFuture<Void> start = pendingStart;
    CompletableFuture<Void> stop = pendingStop;
    pendingStart = null;
    pendingStop = null;
    if (start != null) {
      start.completeExceptionally(cause);
    }
    if (stop != null) {
      stop.completeExceptionally(cause);
    }
  }

  // --- Misc ------------------------------------------------------------------------------------

  private static int increment(int sequenceNumber) {
    return (sequenceNumber + 1) % SEQUENCE_MODULUS;
  }

  private static CompletableFuture<Void> failedFuture(Throwable cause) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    future.completeExceptionally(cause);
    return future;
  }
}
