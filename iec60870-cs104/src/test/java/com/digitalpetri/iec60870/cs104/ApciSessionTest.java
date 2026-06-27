package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolStateException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.SequenceNumberException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApciSession}: sequence-number progression, the {@code k}/{@code w} window,
 * the {@code t1}/{@code t2}/{@code t3} timers, the STARTDT/STOPDT/TESTFR handshakes, and the
 * sequence-error and wraparound edge cases.
 *
 * <p>The session is driven through a {@link ManualScheduler} virtual clock and a recording {@link
 * RecordingOutput}/{@link RecordingEvents} so every emitted APDU and lifecycle event is asserted
 * deterministically with no real time elapsing.
 */
class ApciSessionTest {

  private static final long T1_MILLIS = 15_000;
  private static final long T2_MILLIS = 10_000;
  private static final long T3_MILLIS = 20_000;

  /** k=3, w=2 so the window and acknowledgement thresholds are small and easy to assert. */
  private static final ApciSettings SETTINGS =
      new ApciSettings(
          UShort.valueOf(3),
          UShort.valueOf(2),
          Duration.ofSeconds(30),
          Duration.ofMillis(T1_MILLIS),
          Duration.ofMillis(T2_MILLIS),
          Duration.ofMillis(T3_MILLIS));

  private ManualScheduler scheduler;
  private RecordingOutput output;
  private RecordingEvents events;

  @BeforeEach
  void setUp() {
    scheduler = new ManualScheduler();
    output = new RecordingOutput();
    events = new RecordingEvents();
  }

  private ApciSession newSession(ApciSession.Role role) {
    return new ApciSession(role, SETTINGS, scheduler, output, events);
  }

  /**
   * Drives a CLIENT session through the STARTDT handshake into the started state, so an inbound
   * I-frame is permitted. An I-frame received while data transfer is stopped self-closes the
   * session, so any test that feeds inbound user data must establish data transfer first.
   */
  private void startClientDataTransfer(ApciSession session) {
    session.startDataTransfer();
    session.onApdu(uFrame(UFunction.STARTDT_CON));
  }

  // --- Static helper ---------------------------------------------------------------------------

  @Test
  void sequenceDistanceHandlesWraparound() {
    assertEquals(0, ApciSession.sequenceDistance(5, 5));
    assertEquals(3, ApciSession.sequenceDistance(0, 3));
    assertEquals(1, ApciSession.sequenceDistance(32767, 0));
    assertEquals(2, ApciSession.sequenceDistance(32767, 1));
    assertEquals(32767, ApciSession.sequenceDistance(1, 0));
  }

  // --- V(S)/V(R) progression -------------------------------------------------------------------

  @Test
  void sendingIFramesAdvancesSendSequenceNumber() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    session.sendAsdu(asdu(10));
    session.sendAsdu(asdu(11));

    assertEquals(2, output.iFrames().size());
    assertEquals(0, output.iFrames().get(0).sendSequenceNumber());
    assertEquals(1, output.iFrames().get(1).sendSequenceNumber());
    // No frames received yet, so N(R) stays 0.
    assertEquals(0, output.iFrames().get(0).receiveSequenceNumber());
    assertEquals(0, output.iFrames().get(1).receiveSequenceNumber());
  }

  @Test
  void receivingIFramesAdvancesReceiveSequenceNumberAndDelivers() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();
    startClientDataTransfer(session);

    session.onApdu(iFrame(0, asdu(1)));
    session.onApdu(iFrame(1, asdu(2)));

    assertEquals(2, events.asdus().size());
    // The next sent I-frame carries V(R) = 2 as its N(R).
    session.sendAsdu(asdu(3));
    assertEquals(2, output.iFrames().get(0).receiveSequenceNumber());
  }

  // --- k window block / release ----------------------------------------------------------------

  @Test
  void sendBlocksAtWindowAndReleasesOnAcknowledge() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    // k = 3: three send immediately, the fourth queues.
    session.sendAsdu(asdu(1));
    session.sendAsdu(asdu(2));
    session.sendAsdu(asdu(3));
    session.sendAsdu(asdu(4));

    assertEquals(3, output.iFrames().size());

    // Peer acknowledges the first two frames (N(R) = 2): one window slot reopens, the queued
    // fourth frame flushes with V(S) = 3.
    session.onApdu(sFrame(2));

    assertEquals(4, output.iFrames().size());
    assertEquals(3, output.iFrames().get(3).sendSequenceNumber());
  }

  @Test
  void serverWithholdsIFramesUntilStarted() {
    ApciSession session = newSession(ApciSession.Role.SERVER);
    session.onConnected();

    session.sendAsdu(asdu(1));
    assertEquals(0, output.iFrames().size(), "server must not send I-frames before STARTDT");

    // Controlling station starts data transfer; queued frame flushes.
    session.onApdu(uFrame(UFunction.STARTDT_ACT));
    assertEquals(1, output.iFrames().size());
    assertTrue(session.isDataTransferStarted());
  }

  // --- w-triggered S-frame ---------------------------------------------------------------------

  @Test
  void receivingWFramesTriggersSupervisoryAck() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();
    startClientDataTransfer(session);

    // w = 2: the second received I-frame triggers an S-frame ack with N(R) = 2.
    session.onApdu(iFrame(0, asdu(1)));
    assertTrue(output.sFrames().isEmpty());

    session.onApdu(iFrame(1, asdu(2)));
    assertEquals(1, output.sFrames().size());
    assertEquals(2, output.sFrames().get(0).receiveSequenceNumber());
  }

  // --- t2-triggered S-frame --------------------------------------------------------------------

  @Test
  void t2ElapsingTriggersSupervisoryAck() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();
    startClientDataTransfer(session);

    // One received I-frame (below w) arms t2 but does not ack yet.
    session.onApdu(iFrame(0, asdu(1)));
    assertTrue(output.sFrames().isEmpty());

    scheduler.advance(T2_MILLIS, TimeUnit.MILLISECONDS);

    assertEquals(1, output.sFrames().size());
    assertEquals(1, output.sFrames().get(0).receiveSequenceNumber());
  }

  // --- t1 timeout -> close ---------------------------------------------------------------------

  @Test
  void t1ElapsingOnUnackedIFrameClosesSession() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    session.sendAsdu(asdu(1));
    assertNull(events.lastCloseCause());

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);

    assertInstanceOf(ProtocolTimeoutException.class, events.lastCloseCause());
  }

  @Test
  void acknowledgingBeforeT1DoesNotClose() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    session.sendAsdu(asdu(1));
    session.onApdu(sFrame(1)); // acknowledge before t1

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);
    assertNull(events.lastCloseCause());
  }

  // --- t3 -> TESTFR act, con cancels ----------------------------------------------------------

  @Test
  void t3ElapsingSendsTestFrameActAndConfirmationCancelsTimeout() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    scheduler.advance(T3_MILLIS, TimeUnit.MILLISECONDS);

    assertEquals(1, output.uFrames().size());
    assertEquals(UFunction.TESTFR_ACT, output.uFrames().get(0).function());

    // Confirmation arrives before t1: the session stays open even after t1 would have elapsed.
    session.onApdu(uFrame(UFunction.TESTFR_CON));
    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);
    assertNull(events.lastCloseCause());
  }

  @Test
  void t3TestFrameWithoutConfirmationClosesOnT1() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    scheduler.advance(T3_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(UFunction.TESTFR_ACT, output.uFrames().get(0).function());

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);
    assertInstanceOf(ProtocolTimeoutException.class, events.lastCloseCause());
  }

  @Test
  void receivingTestFrameActRepliesWithConfirmation() {
    ApciSession session = newSession(ApciSession.Role.SERVER);
    session.onConnected();

    session.onApdu(uFrame(UFunction.TESTFR_ACT));

    assertEquals(1, output.uFrames().size());
    assertEquals(UFunction.TESTFR_CON, output.uFrames().get(0).function());
  }

  // --- Unsolicited / mismatched U-frame confirmations must not suppress t1 ---------------------

  @Test
  void unsolicitedTestFrameConfirmationDoesNotSuppressIFrameTimeout() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    // An unacknowledged I-frame arms the shared t1 timer.
    session.sendAsdu(asdu(1));

    // A peer injects a TESTFR con with no TESTFR act outstanding; it must not cancel t1.
    session.onApdu(uFrame(UFunction.TESTFR_CON));

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);
    assertInstanceOf(
        ProtocolTimeoutException.class,
        events.lastCloseCause(),
        "an unsolicited TESTFR con must not suppress the I-frame acknowledgement timeout");
  }

  @Test
  void unsolicitedStartConfirmationDoesNotSuppressIFrameTimeout() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    session.sendAsdu(asdu(1));

    // No STARTDT act was sent, so this STARTDT con is unsolicited and must not cancel t1.
    session.onApdu(uFrame(UFunction.STARTDT_CON));

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);
    assertInstanceOf(
        ProtocolTimeoutException.class,
        events.lastCloseCause(),
        "an unsolicited STARTDT con must not suppress the I-frame acknowledgement timeout");
  }

  @Test
  void unsolicitedStopConfirmationDoesNotSuppressIFrameTimeout() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    session.sendAsdu(asdu(1));

    // No STOPDT act was sent, so this STOPDT con is unsolicited and must not cancel t1.
    session.onApdu(uFrame(UFunction.STOPDT_CON));

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);
    assertInstanceOf(
        ProtocolTimeoutException.class,
        events.lastCloseCause(),
        "an unsolicited STOPDT con must not suppress the I-frame acknowledgement timeout");
  }

  @Test
  void iFrameAckDoesNotSuppressPendingStartTimeout() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    // A STARTDT act is outstanding (awaiting STARTDT con) and shares the single t1 with I-frames.
    CompletionStage<Void> start = session.startDataTransfer();
    session.sendAsdu(asdu(1));

    // Acknowledging the I-frame clears the I-frame obligation but must leave t1 armed for the
    // still-outstanding STARTDT con.
    session.onApdu(sFrame(1));

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);
    assertInstanceOf(
        ProtocolTimeoutException.class,
        events.lastCloseCause(),
        "an I-frame acknowledgement must not suppress the STARTDT confirmation timeout");
    assertTrue(toFuture(start).isCompletedExceptionally());
  }

  // --- STARTDT / STOPDT handshakes -------------------------------------------------------------

  @Test
  void clientStartDataTransferCompletesOnConfirmation() throws Exception {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    CompletionStage<Void> start = session.startDataTransfer();
    assertEquals(UFunction.STARTDT_ACT, output.uFrames().get(0).function());
    assertFalse(toFuture(start).isDone());

    session.onApdu(uFrame(UFunction.STARTDT_CON));

    toFuture(start).get(1, TimeUnit.SECONDS);
    assertTrue(session.isDataTransferStarted());
    assertEquals(List.of(Boolean.TRUE), events.dataTransferChanges());
  }

  @Test
  void clientStartDataTransferTimesOutWhenUnconfirmed() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    CompletionStage<Void> start = session.startDataTransfer();
    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);

    CompletableFuture<Void> future = toFuture(start);
    assertTrue(future.isCompletedExceptionally());
    ExecutionException ex = assertThrows(ExecutionException.class, future::get);
    assertInstanceOf(ProtocolTimeoutException.class, ex.getCause());
    assertInstanceOf(ProtocolTimeoutException.class, events.lastCloseCause());
  }

  @Test
  void clientStopDataTransferCompletesOnConfirmation() throws Exception {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    // Get into the started state first.
    session.startDataTransfer();
    session.onApdu(uFrame(UFunction.STARTDT_CON));
    output.clear();
    events.dataTransferChanges().clear();

    CompletionStage<Void> stop = session.stopDataTransfer();
    assertEquals(UFunction.STOPDT_ACT, output.uFrames().get(0).function());

    session.onApdu(uFrame(UFunction.STOPDT_CON));
    toFuture(stop).get(1, TimeUnit.SECONDS);

    assertFalse(session.isDataTransferStarted());
    assertEquals(List.of(Boolean.FALSE), events.dataTransferChanges());
  }

  @Test
  void serverRepliesConfirmationToStartAndStopActivations() {
    ApciSession session = newSession(ApciSession.Role.SERVER);
    session.onConnected();

    session.onApdu(uFrame(UFunction.STARTDT_ACT));
    assertEquals(UFunction.STARTDT_CON, output.uFrames().get(0).function());
    assertTrue(session.isDataTransferStarted());
    assertEquals(Boolean.TRUE, events.dataTransferChanges().get(0));

    session.onApdu(uFrame(UFunction.STOPDT_ACT));
    assertEquals(UFunction.STOPDT_CON, output.uFrames().get(1).function());
    assertFalse(session.isDataTransferStarted());
    assertEquals(Boolean.FALSE, events.dataTransferChanges().get(1));
  }

  @Test
  void startDataTransferOnServerThrows() {
    // Asserted through the neutral Session contract: a responder/SERVER-role session must reject
    // startDataTransfer/stopDataTransfer with IllegalStateException.
    Session session = newSession(ApciSession.Role.SERVER);
    session.onConnected();

    assertThrows(IllegalStateException.class, session::startDataTransfer);
    assertThrows(IllegalStateException.class, session::stopDataTransfer);
  }

  // --- I-frame while data transfer is stopped -> close without delivery ------------------------

  @Test
  void clientIFrameBeforeStartDataTransferIsRejectedNotDelivered() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    // No STARTDT handshake has completed, so data transfer is stopped. A peer that sends an I-frame
    // now is bypassing the data-transfer gate: the carried ASDU must not reach the application and
    // the session must self-close with a ProtocolStateException.
    session.onApdu(iFrame(0, asdu(1)));

    assertTrue(
        events.asdus().isEmpty(),
        "an I-frame received while data transfer is stopped must not be delivered");
    assertInstanceOf(ProtocolStateException.class, events.lastCloseCause());
  }

  @Test
  void serverIFrameBeforeStartActivationIsRejectedNotDelivered() {
    ApciSession session = newSession(ApciSession.Role.SERVER);
    session.onConnected();

    // The controlling station has not sent STARTDT act, so the server's data transfer is stopped
    // and an inbound I-frame must be rejected rather than dispatched into the station handlers.
    session.onApdu(iFrame(0, asdu(1)));

    assertTrue(
        events.asdus().isEmpty(),
        "a server must not deliver an I-frame received before STARTDT act");
    assertInstanceOf(ProtocolStateException.class, events.lastCloseCause());
  }

  @Test
  void serverIFrameAfterStopActivationIsRejectedNotDelivered() {
    ApciSession session = newSession(ApciSession.Role.SERVER);
    session.onConnected();

    // Start data transfer and deliver one legitimate I-frame.
    session.onApdu(uFrame(UFunction.STARTDT_ACT));
    session.onApdu(iFrame(0, asdu(1)));
    assertEquals(1, events.asdus().size());

    // Once STOPDT act has been processed, data transfer is stopped again; a further I-frame is
    // illegal regardless of its (otherwise in-order) sequence number.
    session.onApdu(uFrame(UFunction.STOPDT_ACT));
    session.onApdu(iFrame(1, asdu(2)));

    assertEquals(
        1, events.asdus().size(), "an I-frame received after STOPDT act must not be delivered");
    assertInstanceOf(ProtocolStateException.class, events.lastCloseCause());
  }

  // --- Received sequence error -> close --------------------------------------------------------

  @Test
  void unexpectedReceiveSequenceNumberClosesSession() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();
    startClientDataTransfer(session);

    // Expected N(S) = 0 but the peer skips to 2.
    session.onApdu(iFrame(2, asdu(1)));

    assertInstanceOf(SequenceNumberException.class, events.lastCloseCause());
  }

  @Test
  void acknowledgingMoreThanSentClosesSession() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    session.sendAsdu(asdu(1)); // V(S) advances to 1

    // Peer claims to have received 5 frames; we only sent 1.
    session.onApdu(sFrame(5));

    assertInstanceOf(SequenceNumberException.class, events.lastCloseCause());
  }

  // --- Wraparound 32767 -> 0 -------------------------------------------------------------------

  @Test
  void sendSequenceNumberWrapsFrom32767ToZero() {
    // Use a wide window so the wrap can be exercised without acknowledgements blocking sends.
    ApciSettings wide =
        new ApciSettings(
            UShort.valueOf(32767),
            UShort.valueOf(30000),
            Duration.ofSeconds(30),
            Duration.ofMillis(T1_MILLIS),
            Duration.ofMillis(T2_MILLIS),
            Duration.ofMillis(T3_MILLIS));
    ApciSession session = new ApciSession(ApciSession.Role.CLIENT, wide, scheduler, output, events);
    session.onConnected();

    // Drive peer acknowledgements forward to keep reopening the window while V(S) climbs to
    // 32767, then wraps to 0.
    for (int i = 0; i <= 32767; i++) {
      session.sendAsdu(asdu(i & 0xFF));
      session.onApdu(sFrame((i + 1) % 32768));
    }
    assertEquals(32767, output.iFrames().get(32767).sendSequenceNumber());

    session.sendAsdu(asdu(0));
    assertEquals(0, output.iFrames().get(32768).sendSequenceNumber());
  }

  @Test
  void receiveSequenceNumberWrapsFrom32767ToZero() {
    ApciSettings wide =
        new ApciSettings(
            UShort.valueOf(32767),
            UShort.valueOf(32767),
            Duration.ofSeconds(30),
            Duration.ofMillis(T1_MILLIS),
            Duration.ofMillis(T2_MILLIS),
            Duration.ofMillis(T3_MILLIS));
    ApciSession session = new ApciSession(ApciSession.Role.CLIENT, wide, scheduler, output, events);
    session.onConnected();
    startClientDataTransfer(session);

    for (int ns = 0; ns <= 32767; ns++) {
      session.onApdu(iFrame(ns, asdu(ns & 0xFF)));
    }
    // V(R) has wrapped back to 0; the next expected N(S) is 0 again.
    session.onApdu(iFrame(0, asdu(0)));
    assertNull(events.lastCloseCause());
    assertEquals(32769, events.asdus().size());
  }

  // --- close() idempotence ---------------------------------------------------------------------

  @Test
  void closeIsIdempotentAndCancelsTimers() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();
    session.sendAsdu(asdu(1));

    session.close();
    session.close(); // no exception

    // Timers cancelled: advancing the clock does not fire t1.
    scheduler.advance(T1_MILLIS * 2, TimeUnit.MILLISECONDS);
    assertNull(events.lastCloseCause(), "close() must not invoke onClosed");
  }

  // --- F3: stale t1 task must not close a re-armed session -------------------------------------

  @Test
  void staleT1TaskDoesNotCloseReArmedSession() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    // Arm a first t1 and capture its (now stale) task.
    session.sendAsdu(asdu(1));
    Runnable staleT1 = scheduler.lastRunnableWithDelay(T1_MILLIS);

    // Acknowledge the first frame, then send a second that re-arms a fresh t1.
    session.onApdu(sFrame(1));
    session.sendAsdu(asdu(2));

    // Running the captured stale t1 task must be a no-op: the session stays open and uncosed.
    staleT1.run();

    assertNull(
        events.lastCloseCause(), "a stale t1 task must not close a healthy re-armed session");
    // The fresh t1 still works: it must be able to time out the genuinely unacknowledged frame.
    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);
    assertInstanceOf(ProtocolTimeoutException.class, events.lastCloseCause());
  }

  // --- F10: a bad N(R) self-close must stop delivery on the same I-frame ------------------------

  @Test
  void badAcknowledgementOnIFrameClosesWithoutDelivering() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();
    startClientDataTransfer(session);

    session.sendAsdu(asdu(1)); // V(S) advances to 1; one frame outstanding

    // Valid N(S)=0 but N(R)=5 acknowledges more frames than were ever sent.
    session.onApdu(iFrame(0, 5, asdu(2)));

    assertInstanceOf(SequenceNumberException.class, events.lastCloseCause());
    assertTrue(
        events.asdus().isEmpty(),
        "the ASDU on a frame whose N(R) self-closed the session must not be delivered");

    // No supervisory S-frame is emitted on the closed session even as the clock advances.
    scheduler.advance(T2_MILLIS, TimeUnit.MILLISECONDS);
    assertTrue(output.sFrames().isEmpty());
  }

  // --- F20: a synchronous send failure that closes the session must not leak timers -------------

  @Test
  void synchronousSendFailureClosingSessionLeavesNoLiveTimers() {
    ManualScheduler local = new ManualScheduler();
    RecordingEvents localEvents = new RecordingEvents();
    AtomicReference<@Nullable ApciSession> ref = new AtomicReference<>();
    // An Output that closes the session synchronously on the very first I-frame send (mirrors a
    // transport send future that has already failed and re-enters handleConnectionLost -> close()).
    ApciSession.Output closingOutput =
        apdu -> {
          if (apdu.control() instanceof ControlField.TypeI) {
            ApciSession s = ref.get();
            if (s != null) {
              s.close();
            }
          }
        };
    ApciSession session =
        new ApciSession(ApciSession.Role.CLIENT, SETTINGS, local, closingOutput, localEvents);
    ref.set(session);
    session.onConnected();

    session.sendAsdu(asdu(1));

    // The re-entrant close must stop flushSendQueue before it arms t1/t3 on a closed session.
    assertEquals(
        0, local.pendingTaskCount(), "a re-entrant close during send must leave no live timers");
  }

  // --- F5: bounded outbound send queue honors the overflow policy ------------------------------

  @Test
  void boundedSendQueueDropsOldestOnOverflow() {
    // bound = 2; k-window stuck closed (server, never started) so nothing drains.
    ApciSession session =
        new ApciSession(
            ApciSession.Role.SERVER,
            SETTINGS,
            scheduler,
            output,
            events,
            2,
            OutboundQueuePolicy.DROP_OLDEST);
    session.onConnected();

    for (int i = 0; i < 5; i++) {
      session.sendAsdu(asdu(i));
    }

    assertEquals(2, session.pendingSendCount(), "DROP_OLDEST must never exceed the bound");
    assertTrue(output.iFrames().isEmpty(), "server withholds I-frames before STARTDT");

    // Start data transfer; the two retained ASDUs are the most recent (ioa 3 and 4).
    session.onApdu(uFrame(UFunction.STARTDT_ACT));
    List<ControlField.TypeI> frames = output.iFrames();
    assertEquals(2, frames.size());
  }

  @Test
  void boundedSendQueueDropsNewestOnOverflow() {
    ApciSession session =
        new ApciSession(
            ApciSession.Role.SERVER,
            SETTINGS,
            scheduler,
            output,
            events,
            2,
            OutboundQueuePolicy.DROP_NEWEST);
    session.onConnected();

    for (int i = 0; i < 5; i++) {
      session.sendAsdu(asdu(i));
    }

    assertEquals(2, session.pendingSendCount(), "DROP_NEWEST must never exceed the bound");
  }

  @Test
  void blockPolicyParkedPublisherUnblocksWhenQueueDrains() throws Exception {
    // bound = 1, k = 3 so the window is open: the first ASDU transmits immediately, the publisher
    // then awaits capacity for the next while the queue is empty -> returns true without parking.
    ApciSession session =
        new ApciSession(
            ApciSession.Role.CLIENT,
            SETTINGS,
            scheduler,
            output,
            events,
            1,
            OutboundQueuePolicy.BLOCK);
    session.onConnected();

    // Stuff the queue beyond the bound while the window stays closed by acking nothing: send k=3
    // frames (all transmit) then a fourth queues, reaching the bound.
    session.sendAsdu(asdu(1));
    session.sendAsdu(asdu(2));
    session.sendAsdu(asdu(3));
    session.sendAsdu(asdu(4)); // queued; pending == 1 == bound

    assertEquals(1, session.pendingSendCount());

    // A publisher thread parks awaiting capacity; a peer ack drains a slot and wakes it.
    CompletableFuture<Boolean> parked =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return session.awaitSendCapacity(5_000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
              }
            });

    // Give the publisher a moment to park, then acknowledge so the queued frame flushes.
    Thread.sleep(50);
    session.onApdu(sFrame(1)); // acks one frame; window reopens; queued asdu(4) flushes

    assertTrue(parked.get(5, TimeUnit.SECONDS), "the parked publisher must unblock, not deadlock");
  }

  @Test
  void boundedSendQueueUnderBlockPolicyDropsNewestAsLastResort() {
    // BLOCK shares the last-resort drop-newest guard in sendAsdu (the switch's DROP_NEWEST, BLOCK
    // case): if a publisher offers past the bound without first awaiting capacity, the newly
    // offered ASDU is dropped so the bound is never exceeded. SERVER role withholds I-frames before
    // STARTDT, so every offered ASDU lands in the send queue and the bound is exercised directly.
    ApciSession session =
        new ApciSession(
            ApciSession.Role.SERVER,
            SETTINGS,
            scheduler,
            output,
            events,
            2,
            OutboundQueuePolicy.BLOCK);
    session.onConnected();

    for (int i = 0; i < 5; i++) {
      session.sendAsdu(asdu(i));
    }

    assertEquals(
        2, session.pendingSendCount(), "BLOCK's last-resort guard must never exceed the bound");
    assertTrue(output.iFrames().isEmpty(), "server withholds I-frames before STARTDT");

    // The retained ASDUs are the earliest two (ioa 0 and 1); newest offers were dropped. Starting
    // data transfer flushes exactly the bounded history.
    session.onApdu(uFrame(UFunction.STARTDT_ACT));
    assertEquals(2, output.iFrames().size());
  }

  @Test
  void awaitSendCapacityReturnsFalseWhenQueueStaysFullPastTimeout() throws Exception {
    // bound = 1: stuff the queue to the bound with the window kept closed (no acks) so it can never
    // drain, then await a short timeout. Because the queue is genuinely full-and-stuck, the false
    // return is guaranteed regardless of timing; the only timing dependence is a harmless lower
    // bound on elapsed wall time, asserted as a terminal outcome rather than an exact duration.
    ApciSession session =
        new ApciSession(
            ApciSession.Role.CLIENT,
            SETTINGS,
            scheduler,
            output,
            events,
            1,
            OutboundQueuePolicy.BLOCK);
    session.onConnected();

    // k = 3: three I-frames transmit immediately, the fourth queues and reaches the bound. No acks
    // arrive, so the window stays closed and the queue can never drain.
    session.sendAsdu(asdu(1));
    session.sendAsdu(asdu(2));
    session.sendAsdu(asdu(3));
    session.sendAsdu(asdu(4));

    assertEquals(1, session.pendingSendCount(), "the queue is at its bound and cannot drain");

    long start = System.nanoTime();
    boolean capacity = session.awaitSendCapacity(40);
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertFalse(capacity, "a stuck full queue must time out, returning false");
    assertTrue(elapsedMillis >= 30, "the await must respect the bounded timeout before giving up");
    assertEquals(1, session.pendingSendCount(), "the timed-out wait must not alter the queue");
  }

  @Test
  void awaitSendCapacityThrowsInterruptedExceptionWhenParkedPublisherIsInterrupted()
      throws Exception {
    // bound = 1: park a publisher on a full-and-stuck queue (window closed, no acks), then
    // interrupt it and assert InterruptedException propagates out of awaitSendCapacity.
    ApciSession session =
        new ApciSession(
            ApciSession.Role.CLIENT,
            SETTINGS,
            scheduler,
            output,
            events,
            1,
            OutboundQueuePolicy.BLOCK);
    session.onConnected();

    session.sendAsdu(asdu(1));
    session.sendAsdu(asdu(2));
    session.sendAsdu(asdu(3));
    session.sendAsdu(asdu(4)); // queued; pending == 1 == bound

    assertEquals(1, session.pendingSendCount());

    CountDownLatch parked = new CountDownLatch(1);
    AtomicReference<@Nullable Throwable> thrown = new AtomicReference<>();
    Thread publisher =
        new Thread(
            () -> {
              parked.countDown();
              try {
                // A long timeout so the wait only ends via interruption, never a timeout.
                session.awaitSendCapacity(60_000);
              } catch (Throwable t) {
                thrown.set(t);
              }
            },
            "block-publisher");
    publisher.start();

    // Wait until the publisher has started, then give it a moment to enter the parked await before
    // interrupting; the interrupt must surface as InterruptedException regardless of exact timing.
    assertTrue(parked.await(5, TimeUnit.SECONDS), "the publisher thread must start");
    Thread.sleep(50);
    publisher.interrupt();
    publisher.join(5_000);

    assertFalse(publisher.isAlive(), "the interrupted publisher must terminate");
    assertInstanceOf(
        InterruptedException.class,
        thrown.get(),
        "interrupting a parked publisher must raise InterruptedException");
    assertEquals(
        1, session.pendingSendCount(), "the interrupted wait must leave the queue untouched");
  }

  // --- Fixtures --------------------------------------------------------------------------------

  private static Asdu asdu(int ioa) {
    InformationObject object = new ReadCommand(InformationObjectAddress.of(ioa));
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(object));
  }

  private static Apdu iFrame(int ns, Asdu asdu) {
    return new Apdu(new ControlField.TypeI(ns, 0), asdu);
  }

  private static Apdu iFrame(int ns, int nr, Asdu asdu) {
    return new Apdu(new ControlField.TypeI(ns, nr), asdu);
  }

  private static Apdu sFrame(int nr) {
    return new Apdu(new ControlField.TypeS(nr), null);
  }

  private static Apdu uFrame(UFunction function) {
    return new Apdu(new ControlField.TypeU(function), null);
  }

  private static CompletableFuture<Void> toFuture(CompletionStage<Void> stage) {
    return stage.toCompletableFuture();
  }

  /** Records every APDU the session emits, partitioned by control-field type. */
  private static final class RecordingOutput implements ApciSession.Output {

    private final List<Apdu> apdus = new ArrayList<>();

    @Override
    public void send(Apdu apdu) {
      apdus.add(apdu);
    }

    void clear() {
      apdus.clear();
    }

    List<ControlField.TypeI> iFrames() {
      List<ControlField.TypeI> out = new ArrayList<>();
      for (Apdu apdu : apdus) {
        if (apdu.control() instanceof ControlField.TypeI i) {
          out.add(i);
        }
      }
      return out;
    }

    List<ControlField.TypeS> sFrames() {
      List<ControlField.TypeS> out = new ArrayList<>();
      for (Apdu apdu : apdus) {
        if (apdu.control() instanceof ControlField.TypeS s) {
          out.add(s);
        }
      }
      return out;
    }

    List<ControlField.TypeU> uFrames() {
      List<ControlField.TypeU> out = new ArrayList<>();
      for (Apdu apdu : apdus) {
        if (apdu.control() instanceof ControlField.TypeU u) {
          out.add(u);
        }
      }
      return out;
    }
  }

  /** Guards against accidental reference equality assumptions in fixtures. */
  @Test
  void asduFixturesAreDistinctInstances() {
    Asdu a = asdu(1);
    Asdu b = asdu(1);
    assertNotSame(a, b);
  }
}
