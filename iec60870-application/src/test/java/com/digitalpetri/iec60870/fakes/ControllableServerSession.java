package com.digitalpetri.iec60870.fakes;

import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.session.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A SERVER-role {@link Session} fake whose {@link #awaitSendCapacity(long)} is fully controllable,
 * so a facade test can drive the BLOCK-backpressure branches of {@code
 * DefaultIec60870Server.ServerConnection.enqueueMonitor}.
 *
 * <p>Unlike {@link FakeSession}, whose {@code awaitSendCapacity} never blocks, this fake exposes a
 * selectable wait outcome:
 *
 * <ul>
 *   <li>{@link Outcome#RETURN_TRUE} — capacity is reported available immediately.
 *   <li>{@link Outcome#RETURN_FALSE} — the wait "times out" (returns {@code false}) so the server
 *       falls through to {@link #sendAsdu(Asdu)} and the session's last-resort bound applies.
 *   <li>{@link Outcome#THROW_INTERRUPTED} — the wait throws {@link InterruptedException}.
 *   <li>{@link Outcome#BLOCK_UNTIL_RELEASED} — the wait parks on a latch until the test calls
 *       {@link #releaseAwait()}, modeling a publisher held under backpressure (so the test can
 *       close the connection underneath it and observe the closed-after-wait branch).
 * </ul>
 *
 * <p>The fake reports {@code isDataTransferStarted() == true} once {@link
 * #simulateDataTransferStarted()} runs, so {@code enqueueMonitor} proceeds past its data-transfer
 * gate. Sent ASDUs are captured and the number of {@code awaitSendCapacity} invocations is tracked
 * for assertions.
 */
public final class ControllableServerSession implements Session {

  /** The behavior {@link #awaitSendCapacity(long)} exhibits when invoked. */
  public enum Outcome {
    RETURN_TRUE,
    RETURN_FALSE,
    THROW_INTERRUPTED,
    BLOCK_UNTIL_RELEASED
  }

  private final Session.Events events;
  private final List<Asdu> sent = new ArrayList<>();
  private final AtomicInteger awaitCalls = new AtomicInteger();
  private final CountDownLatch releaseAwait = new CountDownLatch(1);
  private final CountDownLatch awaitEntered = new CountDownLatch(1);

  private volatile Outcome outcome = Outcome.RETURN_TRUE;
  private volatile boolean dataTransferStarted;
  private volatile boolean closed;

  /**
   * Creates the controllable session wired to the supplied facade event sink.
   *
   * @param events the facade's event sink.
   */
  public ControllableServerSession(Session.Events events) {
    this.events = events;
  }

  /**
   * Selects the behavior the next {@link #awaitSendCapacity(long)} call exhibits.
   *
   * @param outcome the wait outcome.
   */
  public void awaitOutcome(Outcome outcome) {
    this.outcome = outcome;
  }

  // --- Session ---------------------------------------------------------------------------------

  @Override
  public void onConnected() {
    dataTransferStarted = false;
    closed = false;
  }

  @Override
  public CompletionStage<Void> startDataTransfer() {
    throw new IllegalStateException("startDataTransfer is invalid on a SERVER-role session");
  }

  @Override
  public CompletionStage<Void> stopDataTransfer() {
    throw new IllegalStateException("stopDataTransfer is invalid on a SERVER-role session");
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
    sent.add(asdu);
  }

  @Override
  public boolean awaitSendCapacity(long timeoutMillis) throws InterruptedException {
    awaitCalls.incrementAndGet();
    awaitEntered.countDown();
    switch (outcome) {
      case RETURN_TRUE:
        return true;
      case RETURN_FALSE:
        return false;
      case THROW_INTERRUPTED:
        throw new InterruptedException("simulated interrupt while awaiting capacity");
      case BLOCK_UNTIL_RELEASED:
        // Park until the test releases the wait, mirroring a publisher held under backpressure.
        if (!releaseAwait.await(5, TimeUnit.SECONDS)) {
          throw new InterruptedException("await was never released by the test");
        }
        return true;
      default:
        throw new AssertionError("unreachable outcome: " + outcome);
    }
  }

  @Override
  public int pendingSendCount() {
    return 0;
  }

  @Override
  public void close() {
    closed = true;
    // Defensively release any parked await so a closed connection never strands a publisher thread.
    releaseAwait.countDown();
  }

  // --- Test affordances ------------------------------------------------------------------------

  /**
   * Drives this session into the data-transfer-started state so {@code enqueueMonitor} proceeds.
   */
  public void simulateDataTransferStarted() {
    if (dataTransferStarted) {
      return;
    }
    dataTransferStarted = true;
    events.onDataTransferStateChanged(true);
  }

  /**
   * Fires a session self-close to the facade through {@link Session.Events#onClosed(Throwable)},
   * mirroring a protocol-error/timeout close routed through the session.
   *
   * @param cause the close cause, or {@code null} for an orderly close.
   */
  public void fireClosed(@Nullable Throwable cause) {
    if (closed) {
      return;
    }
    events.onClosed(cause);
  }

  /** Releases a publisher parked in {@link Outcome#BLOCK_UNTIL_RELEASED}. */
  public void releaseAwait() {
    releaseAwait.countDown();
  }

  /**
   * Blocks until a publisher thread has entered {@link #awaitSendCapacity(long)}, so a test can
   * deterministically act while the publisher is parked.
   *
   * @param timeoutMillis the maximum time to wait.
   * @return {@code true} if a publisher entered the wait within the timeout.
   * @throws InterruptedException if interrupted while waiting.
   */
  public boolean awaitParked(long timeoutMillis) throws InterruptedException {
    return awaitEntered.await(timeoutMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Returns how many times {@link #awaitSendCapacity(long)} was invoked.
   *
   * @return the await invocation count.
   */
  public int awaitCalls() {
    return awaitCalls.get();
  }

  /**
   * Returns the ASDUs handed to {@link #sendAsdu(Asdu)} so far, in order.
   *
   * @return the sent ASDUs.
   */
  public List<Asdu> sentAsdus() {
    return new ArrayList<>(sent);
  }

  /**
   * Reports whether {@link #close()} has been invoked on this session.
   *
   * @return {@code true} if closed.
   */
  public boolean isClosed() {
    return closed;
  }
}
