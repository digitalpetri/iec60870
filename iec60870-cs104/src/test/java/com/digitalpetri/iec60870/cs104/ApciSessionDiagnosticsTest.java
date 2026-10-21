package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.Iec60870Exception;
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
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.diagnostics.LoggingContext;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link ApciDiagnostics} events an {@link ApciSession} reports: timer expiries, protocol
 * errors and their order relative to the close, the full window, ignored U-frames, and discarded
 * send-queue entries.
 */
class ApciSessionDiagnosticsTest {

  private static final long T1_MILLIS = 15_000;
  private static final long T2_MILLIS = 10_000;
  private static final long T3_MILLIS = 20_000;

  /** k=2, w=2 keeps the window small. */
  private static final ApciSettings SETTINGS =
      new ApciSettings(
          UShort.valueOf(2),
          UShort.valueOf(2),
          Duration.ofSeconds(30),
          Duration.ofMillis(T1_MILLIS),
          Duration.ofMillis(T2_MILLIS),
          Duration.ofMillis(T3_MILLIS));

  private final ManualScheduler scheduler = new ManualScheduler();
  private final List<String> log = Collections.synchronizedList(new ArrayList<>());
  private final RecordingDiagnostics diagnostics = new RecordingDiagnostics(log);
  private final List<Apdu> sent = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();

  private final Session.Events events =
      new Session.Events() {
        @Override
        public void onAsdu(Asdu asdu) {}

        @Override
        public void onDataTransferStateChanged(boolean started) {}

        @Override
        public void onProtocolWarning(String message) {
          warnings.add(message);
        }

        @Override
        public void onClosed(@Nullable Throwable cause) {
          log.add("closed");
        }
      };

  private ApciSession session(ApciSession.Role role) {
    return session(role, 0, OutboundQueuePolicy.DROP_OLDEST, diagnostics);
  }

  private ApciSession session(
      ApciSession.Role role,
      int maxOutboundQueue,
      OutboundQueuePolicy policy,
      ApciDiagnostics listener) {
    ApciSession session =
        new ApciSession(
            role,
            SETTINGS,
            scheduler,
            sent::add,
            events,
            maxOutboundQueue,
            policy,
            listener,
            LoggingContext.empty());
    session.onConnected();
    return session;
  }

  private ApciSession startedClient() {
    ApciSession session = session(ApciSession.Role.CLIENT);
    session.startDataTransfer();
    session.onApdu(uFrame(UFunction.STARTDT_CON));
    return session;
  }

  @Test
  void t1ExpiryReportsTheTimerAndTheErrorBeforeTheClose() {
    ApciSession session = session(ApciSession.Role.CLIENT);
    session.startDataTransfer();

    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);

    assertEquals(List.of("timer T1", "protocolError ProtocolTimeoutException", "closed"), log);
    assertInstanceOf(ProtocolTimeoutException.class, diagnostics.protocolErrors.get(0));
  }

  @Test
  void t2ExpiryIsReportedAndSendsTheAcknowledgement() {
    ApciSession session = startedClient();
    session.onApdu(iFrame(0, 0));

    scheduler.advance(T2_MILLIS, TimeUnit.MILLISECONDS);

    assertEquals(List.of("timer T2"), diagnostics.lines("timer"));
    assertEquals(new ControlField.TypeS(1), sent.get(sent.size() - 1).control());
  }

  @Test
  void t3ExpiryIsReportedAndSendsTheTestFrame() {
    session(ApciSession.Role.CLIENT);

    scheduler.advance(T3_MILLIS, TimeUnit.MILLISECONDS);

    assertEquals(List.of("timer T3"), diagnostics.lines("timer"));
    assertEquals(new ControlField.TypeU(UFunction.TESTFR_ACT), sent.get(0).control());
  }

  @Test
  void sendSequenceMismatchIsReportedWithItsException() {
    ApciSession session = startedClient();

    session.onApdu(iFrame(1, 0));

    assertProtocolError(SequenceNumberException.class);
  }

  @Test
  void invalidReceiveSequenceNumberIsReportedWithItsException() {
    ApciSession session = startedClient();

    session.onApdu(new Apdu(new ControlField.TypeS(5), null));

    assertProtocolError(SequenceNumberException.class);
  }

  @Test
  void iFrameWhileStoppedIsReportedWithItsException() {
    ApciSession session = session(ApciSession.Role.CLIENT);

    session.onApdu(iFrame(0, 0));

    assertProtocolError(ProtocolStateException.class);
  }

  @Test
  void fullWindowIsReportedOncePerStall() {
    ApciSession session = startedClient();

    session.sendAsdu(asdu());
    session.sendAsdu(asdu());
    assertEquals(List.of(), diagnostics.lines("windowFull"), "k frames outstanding, none queued");

    session.sendAsdu(asdu());
    session.sendAsdu(asdu());
    assertEquals(List.of("windowFull 1"), diagnostics.lines("windowFull"));
    assertEquals(2, session.pendingSendCount());

    // The acknowledgement opens the window; both queued ASDUs go out and fill it again.
    session.onApdu(new Apdu(new ControlField.TypeS(2), null));
    assertEquals(0, session.pendingSendCount());
    session.sendAsdu(asdu());

    assertEquals(List.of("windowFull 1", "windowFull 1"), diagnostics.lines("windowFull"));
  }

  @Test
  void ignoredClientFramesAreReportedAndRaiseProtocolWarnings() {
    ApciSession session = session(ApciSession.Role.CLIENT);

    session.onApdu(uFrame(UFunction.TESTFR_CON));
    session.onApdu(uFrame(UFunction.STARTDT_CON));
    session.onApdu(uFrame(UFunction.STOPDT_CON));
    session.onApdu(uFrame(UFunction.STARTDT_ACT));
    session.onApdu(uFrame(UFunction.STOPDT_ACT));

    assertEquals(
        List.of(
            "ignored TESTFR_CON",
            "ignored STARTDT_CON",
            "ignored STOPDT_CON",
            "ignored STARTDT_ACT",
            "ignored STOPDT_ACT"),
        diagnostics.lines("ignored"));
    assertEquals(
        List.of(
            "ignored unsolicited TESTFR con",
            "ignored unsolicited STARTDT con",
            "ignored unsolicited STOPDT con",
            "ignored STARTDT act received by a CLIENT session",
            "ignored STOPDT act received by a CLIENT session"),
        warnings);
    assertEquals(List.of(), diagnostics.lines("closed"));
  }

  @Test
  void discardedSendQueueEntriesAreReported() {
    // A server queues before STARTDT; a bound of 1 discards the older of two discardable ASDUs.
    ApciSession session =
        session(ApciSession.Role.SERVER, 1, OutboundQueuePolicy.DROP_OLDEST, diagnostics);

    session.sendAsdu(asdu());
    session.sendAsdu(asdu());

    assertEquals(List.of("asduDropped OUT"), diagnostics.lines("asduDropped"));
  }

  @Test
  void throwingListenerChangesNothing() {
    ApciSession session =
        session(
            ApciSession.Role.CLIENT, 0, OutboundQueuePolicy.DROP_OLDEST, new ThrowingDiagnostics());

    session.startDataTransfer();
    session.onApdu(uFrame(UFunction.STARTDT_CON));
    assertTrue(session.isDataTransferStarted());
    session.onApdu(uFrame(UFunction.TESTFR_CON));
    session.sendAsdu(asdu());
    session.onApdu(iFrame(0, 1));
    scheduler.advance(T2_MILLIS, TimeUnit.MILLISECONDS);

    assertEquals(new ControlField.TypeS(1), sent.get(sent.size() - 1).control());
    assertEquals(List.of(), log, "the session must not close");
  }

  private void assertProtocolError(Class<? extends Iec60870Exception> type) {
    assertEquals(1, diagnostics.protocolErrors.size());
    assertInstanceOf(type, diagnostics.protocolErrors.get(0));
    List<String> tail = log.subList(log.size() - 2, log.size());
    assertEquals(List.of("protocolError " + type.getSimpleName(), "closed"), tail);
  }

  private static Apdu uFrame(UFunction function) {
    return new Apdu(new ControlField.TypeU(function), null);
  }

  private static Apdu iFrame(int ns, int nr) {
    return new Apdu(new ControlField.TypeI(ns, nr), asdu());
  }

  private static Asdu asdu() {
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(new ReadCommand(InformationObjectAddress.of(1))));
  }
}
