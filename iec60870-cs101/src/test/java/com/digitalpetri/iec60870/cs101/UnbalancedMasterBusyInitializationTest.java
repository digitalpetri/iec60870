package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UnbalancedMasterBusyInitializationTest {

  private static final long POLL_MILLIS = 50;
  private static final long CONFIRM_MILLIS = 20;
  private static final long REPEAT_MILLIS = 100;

  private final ManualScheduler scheduler = new ManualScheduler();
  private final List<Ft12Frame> output = new ArrayList<>();
  private final RecordingEvents events = new RecordingEvents();

  @Test
  void busyStatusIsRecheckedUntilReadyBeforeResetAndCommands() {
    UnbalancedMasterEngine master = newMaster(List.of(1));
    master.sendAsdu(command(1));
    master.startDataTransfer();
    assertLastStatus(1);

    // A responsive busy secondary remains eligible even after more replies than maxRetries.
    for (int i = 0; i < 3; i++) {
      int sent = output.size();
      master.onFrame(status(1, true));
      assertEquals(sent, output.size(), "DFC=1 must not trigger reset or an immediate probe");
      scheduler.advance(POLL_MILLIS - 1, TimeUnit.MILLISECONDS);
      assertEquals(sent, output.size());
      scheduler.advance(1, TimeUnit.MILLISECONDS);
      assertEquals(sent + 1, output.size());
      assertLastStatus(1);
    }
    assertEquals(1, master.pendingSendCount());
    assertTrue(events.asdus().isEmpty(), "busy replies must not exhaust the silent-peer budget");

    master.onFrame(status(1, false));
    assertLastFixed(0, 1);
    master.onFrame(ack(1));
    Ft12Frame.Variable data =
        assertInstanceOf(Ft12Frame.Variable.class, output.get(output.size() - 1));
    assertEquals(3, data.control().functionCode());
    assertTrue(data.control().fcb(), "the first post-reset user frame starts with FCB=1");
    assertEquals(command(1), data.asdu());
    assertEquals(0, master.pendingSendCount());
  }

  @Test
  void busySecondaryYieldsImmediatelyToAnotherSlavesBringUpAndCommand() {
    UnbalancedMasterEngine master = newMaster(List.of(1, 2));
    master.sendAsdu(command(2));
    master.startDataTransfer();

    master.onFrame(status(1, true));
    assertLastStatus(2);
    master.onFrame(status(2, false));
    assertLastFixed(0, 2);
    master.onFrame(ack(2));
    Ft12Frame.Variable command =
        assertInstanceOf(Ft12Frame.Variable.class, output.get(output.size() - 1));
    assertEquals(2, command.linkAddress());
    master.onFrame(ack(2));
    assertEquals(4, output.size(), "the busy slave waits for the poll tick");

    scheduler.advance(POLL_MILLIS, TimeUnit.MILLISECONDS);
    // The available slave's due class-2 poll is serviced before deferred initialization.
    assertLastFixed(11, 2);
    master.onFrame(new Ft12Frame.SingleChar());
    assertLastStatus(1);
  }

  @Test
  void secondaryThatStopsAnsweringAfterBusyStillExhaustsItsRetryBudget() {
    UnbalancedMasterEngine master = newMaster(List.of(1));
    master.startDataTransfer();
    master.onFrame(status(1, true));
    assertEquals(1, output.size());

    scheduler.advance(POLL_MILLIS, TimeUnit.MILLISECONDS);
    assertLastStatus(1);
    scheduler.advance(CONFIRM_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(3, output.size(), "one unanswered status request is retransmitted once");
    assertLastStatus(1);
    scheduler.advance(REPEAT_MILLIS, TimeUnit.MILLISECONDS);

    master.sendAsdu(command(1));
    assertEquals(3, output.size(), "a failed slave receives no command");
    assertEquals(1, events.asdus().size());
    assertTrue(events.asdus().get(0).negative());
    assertEquals(Cause.UNKNOWN_COMMON_ADDRESS, events.asdus().get(0).cause());
    assertEquals(0, events.closedCount());
  }

  @Test
  void stopSuspendsDeferredStatusUntilPollingRestarts() {
    UnbalancedMasterEngine master = newMaster(List.of(1));
    master.startDataTransfer();
    master.onFrame(status(1, true));
    Runnable staleTick = scheduler.lastRunnableWithDelay(POLL_MILLIS);
    master.stopDataTransfer();
    scheduler.advance(POLL_MILLIS * 2, TimeUnit.MILLISECONDS);
    staleTick.run();
    assertEquals(1, output.size());

    master.startDataTransfer();
    scheduler.advance(POLL_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(2, output.size());
    assertLastStatus(1);
  }

  @Test
  void reconnectDiscardsBusyDeferralAndInvalidatesOldPollTick() {
    UnbalancedMasterEngine master = newMaster(List.of(1));
    master.startDataTransfer();
    master.onFrame(status(1, true));
    Runnable staleTick = scheduler.lastRunnableWithDelay(POLL_MILLIS);
    output.clear();

    master.onConnected();
    master.startDataTransfer();
    assertEquals(1, output.size(), "new connections request status immediately");
    assertLastStatus(1);
    staleTick.run();
    assertEquals(1, output.size());
    master.onFrame(status(1, false));
    assertLastFixed(0, 1);
  }

  private UnbalancedMasterEngine newMaster(List<Integer> addresses) {
    var master =
        new UnbalancedMasterEngine(
            LinkSettings.unbalanced()
                .slaveAddresses(addresses)
                .pollInterval(Duration.ofMillis(POLL_MILLIS))
                .confirmTimeout(Duration.ofMillis(CONFIRM_MILLIS))
                .repeatTimeout(Duration.ofMillis(REPEAT_MILLIS))
                .maxRetries(1)
                .build(),
            scheduler,
            output::add,
            events,
            0,
            OutboundQueuePolicy.DROP_OLDEST);
    master.onConnected();
    return master;
  }

  private void assertLastStatus(int address) {
    assertLastFixed(9, address);
  }

  private void assertLastFixed(int functionCode, int address) {
    Ft12Frame.FixedLength frame =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.get(output.size() - 1));
    assertEquals(functionCode, frame.control().functionCode());
    assertEquals(address, frame.linkAddress());
  }

  private static Ft12Frame status(int address, boolean dfc) {
    return new Ft12Frame.FixedLength(LinkControlField.secondary(false, false, dfc, 11), address);
  }

  private static Ft12Frame ack(int address) {
    return new Ft12Frame.FixedLength(LinkControlField.secondary(false, false, false, 0), address);
  }

  private static Asdu command(int address) {
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(address),
        List.of(new ReadCommand(InformationObjectAddress.of(10))));
  }
}
