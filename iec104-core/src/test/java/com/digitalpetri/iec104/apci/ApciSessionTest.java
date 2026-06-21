package com.digitalpetri.iec104.apci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec104.ApciSettings;
import com.digitalpetri.iec104.ProtocolTimeoutException;
import com.digitalpetri.iec104.SequenceNumberException;
import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.address.OriginatorAddress;
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.object.ReadCommand;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

    session.onApdu(iFrame(0, 0, asdu(1)));
    session.onApdu(iFrame(1, 0, asdu(2)));

    assertEquals(2, events.asdus.size());
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

    // w = 2: the second received I-frame triggers an S-frame ack with N(R) = 2.
    session.onApdu(iFrame(0, 0, asdu(1)));
    assertTrue(output.sFrames().isEmpty());

    session.onApdu(iFrame(1, 0, asdu(2)));
    assertEquals(1, output.sFrames().size());
    assertEquals(2, output.sFrames().get(0).receiveSequenceNumber());
  }

  // --- t2-triggered S-frame --------------------------------------------------------------------

  @Test
  void t2ElapsingTriggersSupervisoryAck() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    // One received I-frame (below w) arms t2 but does not ack yet.
    session.onApdu(iFrame(0, 0, asdu(1)));
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
    assertNull(events.closeCause.get());

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);

    assertInstanceOf(ProtocolTimeoutException.class, events.closeCause.get());
  }

  @Test
  void acknowledgingBeforeT1DoesNotClose() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    session.sendAsdu(asdu(1));
    session.onApdu(sFrame(1)); // acknowledge before t1

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);
    assertNull(events.closeCause.get());
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
    assertNull(events.closeCause.get());
  }

  @Test
  void t3TestFrameWithoutConfirmationClosesOnT1() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    scheduler.advance(T3_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(UFunction.TESTFR_ACT, output.uFrames().get(0).function());

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);
    assertInstanceOf(ProtocolTimeoutException.class, events.closeCause.get());
  }

  @Test
  void receivingTestFrameActRepliesWithConfirmation() {
    ApciSession session = newSession(ApciSession.Role.SERVER);
    session.onConnected();

    session.onApdu(uFrame(UFunction.TESTFR_ACT));

    assertEquals(1, output.uFrames().size());
    assertEquals(UFunction.TESTFR_CON, output.uFrames().get(0).function());
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
    assertEquals(List.of(Boolean.TRUE), events.dataTransferChanges);
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
    assertInstanceOf(ProtocolTimeoutException.class, events.closeCause.get());
  }

  @Test
  void clientStopDataTransferCompletesOnConfirmation() throws Exception {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    // Get into the started state first.
    session.startDataTransfer();
    session.onApdu(uFrame(UFunction.STARTDT_CON));
    output.clear();
    events.dataTransferChanges.clear();

    CompletionStage<Void> stop = session.stopDataTransfer();
    assertEquals(UFunction.STOPDT_ACT, output.uFrames().get(0).function());

    session.onApdu(uFrame(UFunction.STOPDT_CON));
    toFuture(stop).get(1, TimeUnit.SECONDS);

    assertFalse(session.isDataTransferStarted());
    assertEquals(List.of(Boolean.FALSE), events.dataTransferChanges);
  }

  @Test
  void serverRepliesConfirmationToStartAndStopActivations() {
    ApciSession session = newSession(ApciSession.Role.SERVER);
    session.onConnected();

    session.onApdu(uFrame(UFunction.STARTDT_ACT));
    assertEquals(UFunction.STARTDT_CON, output.uFrames().get(0).function());
    assertTrue(session.isDataTransferStarted());
    assertEquals(Boolean.TRUE, events.dataTransferChanges.get(0));

    session.onApdu(uFrame(UFunction.STOPDT_ACT));
    assertEquals(UFunction.STOPDT_CON, output.uFrames().get(1).function());
    assertFalse(session.isDataTransferStarted());
    assertEquals(Boolean.FALSE, events.dataTransferChanges.get(1));
  }

  @Test
  void startDataTransferOnServerThrows() {
    ApciSession session = newSession(ApciSession.Role.SERVER);
    session.onConnected();

    assertThrows(IllegalStateException.class, session::startDataTransfer);
  }

  // --- Received sequence error -> close --------------------------------------------------------

  @Test
  void unexpectedReceiveSequenceNumberClosesSession() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    // Expected N(S) = 0 but the peer skips to 2.
    session.onApdu(iFrame(2, 0, asdu(1)));

    assertInstanceOf(SequenceNumberException.class, events.closeCause.get());
  }

  @Test
  void acknowledgingMoreThanSentClosesSession() {
    ApciSession session = newSession(ApciSession.Role.CLIENT);
    session.onConnected();

    session.sendAsdu(asdu(1)); // V(S) advances to 1

    // Peer claims to have received 5 frames; we only sent 1.
    session.onApdu(sFrame(5));

    assertInstanceOf(SequenceNumberException.class, events.closeCause.get());
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

    // Drive the peer's acknowledgements forward so the window keeps reopening while V(S) climbs to
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

    for (int ns = 0; ns <= 32767; ns++) {
      session.onApdu(iFrame(ns, 0, asdu(ns & 0xFF)));
    }
    // V(R) has wrapped back to 0; the next expected N(S) is 0 again.
    session.onApdu(iFrame(0, 0, asdu(0)));
    assertNull(events.closeCause.get());
    assertEquals(32769, events.asdus.size());
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
    assertNull(events.closeCause.get(), "close() must not invoke onClosed");
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

  /** Records delivered ASDUs, data-transfer transitions, and the close cause. */
  private static final class RecordingEvents implements ApciSession.Events {

    private final List<Asdu> asdus = new ArrayList<>();
    private final List<Boolean> dataTransferChanges = new ArrayList<>();
    private final AtomicReference<@Nullable Throwable> closeCause = new AtomicReference<>();

    @Override
    public void onAsdu(Asdu asdu) {
      asdus.add(asdu);
    }

    @Override
    public void onDataTransferStateChanged(boolean started) {
      dataTransferChanges.add(started);
    }

    @Override
    public void onClosed(@Nullable Throwable cause) {
      closeCause.set(cause);
    }
  }

  /** Guards against accidental reference equality assumptions in fixtures. */
  @Test
  void asduFixturesAreDistinctInstances() {
    Asdu a = asdu(1);
    Asdu b = asdu(1);
    assertSame(a, a);
    assertFalse(a == b);
  }
}
