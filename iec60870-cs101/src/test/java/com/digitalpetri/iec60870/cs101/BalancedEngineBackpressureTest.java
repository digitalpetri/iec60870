package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BalancedEngineBackpressureTest {

  @Test
  void busyAckProbesStatusBeforeSendingTheNextAsduWithToggledFcb() {
    var fixture = new Fixture();
    fixture.bringUp();
    Asdu first = readAsdu(1);
    Asdu second = readAsdu(2);
    fixture.engine.sendAsdu(first);
    fixture.engine.sendAsdu(second);
    assertTrue(fixture.takeData().control().fcb());

    fixture.reply(0, true);
    fixture.takeStatusProbe();
    assertEquals(1, fixture.engine.pendingSendCount());
    fixture.assertNoOutput();

    // E5 and a stale data ACK do not answer a status probe or clear backpressure.
    fixture.engine.onFrame(new Ft12Frame.SingleChar());
    fixture.reply(0, false);
    fixture.assertNoOutput();
    fixture.reply(11, false);
    Ft12Frame.Variable resumed = fixture.takeData();
    assertEquals(second, resumed.asdu());
    assertFalse(resumed.control().fcb(), "the accepted first ASDU spent FCB=1");
    fixture.reply(0, false);
    fixture.scheduler.advance(4000, TimeUnit.MILLISECONDS);
    fixture.assertNoOutput();
    assertEquals(0, fixture.events.closedCount());
  }

  @Test
  void nackProbesStatusAndRetriesTheRejectedAsduWithItsOriginalFcb() {
    for (boolean dfc : List.of(false, true)) {
      var fixture = new Fixture();
      fixture.bringUp();
      fixture.engine.sendAsdu(readAsdu(1));
      fixture.takeData();
      fixture.reply(0, false);

      fixture.engine.sendAsdu(readAsdu(2));
      Ft12Frame.Variable rejected = fixture.takeData();
      assertFalse(rejected.control().fcb());
      Asdu queued = readAsdu(3);
      fixture.engine.sendAsdu(queued);
      fixture.reply(1, dfc);
      fixture.takeStatusProbe();
      fixture.reply(11, true);
      fixture.scheduler.advance(1000, TimeUnit.MILLISECONDS);
      fixture.takeStatusProbe();
      fixture.assertNoOutput();

      fixture.reply(11, false);
      assertEquals(rejected, fixture.takeData(), "NACK does not spend the data FCB");
      fixture.assertNoOutput();
      fixture.reply(0, false);
      Ft12Frame.Variable next = fixture.takeData();
      assertEquals(queued, next.asdu());
      assertTrue(next.control().fcb());
      fixture.reply(0, false);
      assertEquals(0, fixture.events.closedCount());
    }
  }

  @Test
  void responsiveBusyPeerIsProbedAtRepeatIntervalsWithoutExhaustingRetries() {
    var fixture = new Fixture(0);
    fixture.bringUp();
    fixture.engine.sendAsdu(readAsdu(1));
    fixture.takeData();
    fixture.reply(0, true);
    fixture.takeStatusProbe();
    fixture.engine.sendAsdu(readAsdu(2));

    for (int i = 0; i < 5; i++) {
      fixture.reply(11, true);
      fixture.scheduler.advance(999, TimeUnit.MILLISECONDS);
      fixture.assertNoOutput();
      fixture.scheduler.advance(1, TimeUnit.MILLISECONDS);
      fixture.takeStatusProbe();
      fixture.assertNoOutput();
      assertEquals(0, fixture.events.closedCount(), "busy replies establish liveness");
      assertEquals(1, fixture.engine.pendingSendCount());
    }

    fixture.reply(11, false);
    assertFalse(fixture.takeData().control().fcb());
    fixture.reply(0, false);
  }

  @Test
  void busyStatusDuringBringUpDefersResetUntilThePeerIsReady() {
    var fixture = new Fixture(0);
    CompletableFuture<Void> start = fixture.engine.startDataTransfer().toCompletableFuture();
    fixture.takeStatusProbe();
    fixture.engine.sendAsdu(readAsdu(1));

    for (int i = 0; i < 3; i++) {
      fixture.reply(11, true);
      fixture.assertNoOutput();
      assertFalse(start.isDone());
      fixture.scheduler.advance(1000, TimeUnit.MILLISECONDS);
      fixture.takeStatusProbe();
      assertEquals(0, fixture.events.closedCount());
    }

    fixture.reply(11, false);
    fixture.takePrimary(0);
    fixture.reply(0, false);
    assertTrue(start.isDone());
    assertFalse(start.isCompletedExceptionally());
    assertTrue(fixture.takeData().control().fcb());
  }

  @Test
  void busyResetAckCompletesBringUpButDefersQueuedData() {
    var fixture = new Fixture();
    CompletableFuture<Void> start = fixture.engine.startDataTransfer().toCompletableFuture();
    fixture.takeStatusProbe();
    fixture.reply(11, false);
    fixture.takePrimary(0);
    fixture.engine.sendAsdu(readAsdu(1));

    fixture.reply(0, true);
    assertTrue(start.isDone());
    assertFalse(start.isCompletedExceptionally());
    fixture.takeStatusProbe();
    fixture.assertNoOutput();
    assertEquals(1, fixture.engine.pendingSendCount());
    fixture.reply(11, false);
    assertTrue(fixture.takeData().control().fcb());
  }

  @Test
  void busyKeepaliveStatusBlocksQueuedAndNewDataUntilAReadyStatus() {
    var fixture = new Fixture();
    fixture.bringUp();
    fixture.scheduler.advance(5000, TimeUnit.MILLISECONDS);
    fixture.takeStatusProbe();
    fixture.engine.sendAsdu(readAsdu(1));
    fixture.reply(11, true);
    fixture.engine.sendAsdu(readAsdu(2));
    fixture.assertNoOutput();
    assertEquals(2, fixture.engine.pendingSendCount());

    fixture.scheduler.advance(1000, TimeUnit.MILLISECONDS);
    fixture.takeStatusProbe();
    fixture.reply(11, false);
    assertTrue(fixture.takeData().control().fcb());
    fixture.reply(0, false);
    assertFalse(fixture.takeData().control().fcb());
    fixture.reply(0, false);
    assertEquals(0, fixture.events.closedCount());
  }

  @Test
  void unansweredBusyStatusProbesStillTimeOut() {
    var fixture = new Fixture(1);
    fixture.bringUp();
    fixture.engine.sendAsdu(readAsdu(1));
    fixture.takeData();
    fixture.reply(0, true);
    fixture.takeStatusProbe();
    fixture.engine.sendAsdu(readAsdu(2));

    fixture.scheduler.advance(200, TimeUnit.MILLISECONDS);
    fixture.takeStatusProbe();
    assertEquals(0, fixture.events.closedCount());
    fixture.scheduler.advance(1000, TimeUnit.MILLISECONDS);
    assertEquals(1, fixture.events.closedCount());
    assertInstanceOf(ProtocolTimeoutException.class, fixture.events.lastCloseCause());
    fixture.assertNoOutput();
  }

  @Test
  void closeCancelsBusyProbeAndReconnectStartsWithFreshTimerState() {
    var fixture = new Fixture(0);
    fixture.bringUp();
    fixture.engine.sendAsdu(readAsdu(1));
    fixture.takeData();
    fixture.reply(0, true);
    fixture.takeStatusProbe();
    fixture.reply(11, true);
    fixture.engine.close();
    fixture.scheduler.advance(1000, TimeUnit.MILLISECONDS);
    fixture.assertNoOutput();

    fixture.engine.onConnected();
    fixture.engine.startDataTransfer();
    fixture.takeStatusProbe();
    // With no response and maxRetries=0, a fresh initial status request must time out at 200 ms.
    fixture.scheduler.advance(200, TimeUnit.MILLISECONDS);
    assertEquals(1, fixture.events.closedCount());
    assertInstanceOf(ProtocolTimeoutException.class, fixture.events.lastCloseCause());
    fixture.assertNoOutput();
  }

  private static Asdu readAsdu(int ioa) {
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(new ReadCommand(InformationObjectAddress.of(ioa))));
  }

  private static final class Fixture {
    private final ManualScheduler scheduler = new ManualScheduler();
    private final ArrayDeque<Ft12Frame> output = new ArrayDeque<>();
    private final RecordingEvents events = new RecordingEvents();
    private final BalancedEngine engine;

    Fixture() {
      this(3);
    }

    Fixture(int maxRetries) {
      engine =
          new BalancedEngine(
              Ft12LinkLayer.Role.CLIENT,
              LinkSettings.balanced().maxRetries(maxRetries).build(),
              scheduler,
              output::addLast,
              events);
      engine.onConnected();
    }

    void bringUp() {
      engine.startDataTransfer();
      takeStatusProbe();
      reply(11, false);
      takePrimary(0);
      reply(0, false);
      assertTrue(engine.isDataTransferStarted());
      assertNoOutput();
    }

    void reply(int fc, boolean dfc) {
      engine.onFrame(
          new Ft12Frame.FixedLength(LinkControlField.secondary(false, false, dfc, fc), 1));
    }

    void takeStatusProbe() {
      takePrimary(9);
    }

    void takePrimary(int fc) {
      assertFalse(output.isEmpty(), "expected primary FC" + fc);
      Ft12Frame.FixedLength frame =
          assertInstanceOf(Ft12Frame.FixedLength.class, output.removeFirst());
      assertTrue(frame.control().prm());
      assertEquals(fc, frame.control().functionCode());
      assertFalse(frame.control().fcv());
    }

    Ft12Frame.Variable takeData() {
      assertFalse(output.isEmpty(), "expected user data");
      return assertInstanceOf(Ft12Frame.Variable.class, output.removeFirst());
    }

    void assertNoOutput() {
      assertTrue(output.isEmpty(), () -> "unexpected frames: " + output);
    }
  }
}
