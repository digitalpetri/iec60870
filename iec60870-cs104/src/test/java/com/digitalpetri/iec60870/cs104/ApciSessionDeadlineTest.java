package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ApciSessionDeadlineTest {

  private final ManualScheduler scheduler = new ManualScheduler();
  private final RecordingEvents events = new RecordingEvents();
  private final List<Apdu> sent = new ArrayList<>();
  private final ApciSession session =
      new ApciSession(
          ApciSession.Role.CLIENT, ApciSettings.defaults(), scheduler, sent::add, events);

  @Test
  void sendingAnotherIFramePreservesOldestDeadline() {
    start();
    send();
    advance(14);
    send();

    assertOpen();
    advance(1);
    assertTimedOut();
  }

  @Test
  void partialAcknowledgementPreservesRemainingFrameDeadline() {
    start();
    send();
    send();
    advance(14);
    acknowledge(1);

    assertOpen();
    advance(1);
    assertTimedOut();
  }

  @Test
  void partialAcknowledgementUsesNextFramesOriginalSendTime() {
    start();
    send();
    advance(5);
    send();
    advance(9);
    acknowledge(1);

    advance(5); // t=19: the remaining frame was sent at t=5.
    assertOpen();
    advance(1);
    assertTimedOut();
  }

  @Test
  void flushingWindowAfterPartialAcknowledgementPreservesRemainingDeadline() {
    start();
    send();
    advance(5);
    for (int i = 0; i < 12; i++) {
      send();
    }
    assertEquals(1, session.pendingSendCount());
    advance(9);
    acknowledge(1);
    assertEquals(0, session.pendingSendCount());

    advance(5);
    assertOpen();
    advance(1);
    assertTimedOut();
  }

  @Test
  void fullAcknowledgementAllowsNextFrameItsOwnDeadline() {
    start();
    send();
    advance(14);
    acknowledge(1);
    send();

    advance(14);
    assertOpen();
    advance(1);
    assertTimedOut();
  }

  @Test
  void acknowledgedTrafficDoesNotExtendTestFrameDeadline() {
    start();
    advance(20);
    assertEquals(new ControlField.TypeU(UFunction.TESTFR_ACT), sent.get(sent.size() - 1).control());
    advance(14);
    send();
    acknowledge(1);

    assertOpen();
    advance(1); // TESTFR sent at t=20 must expire at t=35.
    assertTimedOut();
  }

  @Test
  void testConfirmationLeavesNewerIFrameItsOwnDeadline() {
    start();
    advance(20);
    advance(14);
    send();
    confirm(UFunction.TESTFR_CON);

    advance(14); // t=48: the remaining I-frame was sent at t=34.
    assertOpen();
    advance(1);
    assertTimedOut();
  }

  @Test
  void acknowledgedTrafficDoesNotExtendStartDeadline() {
    session.onConnected();
    CompletableFuture<Void> start = session.startDataTransfer().toCompletableFuture();
    advance(14);
    send();
    acknowledge(1);

    advance(1);
    assertTimedOut();
    assertTrue(start.isCompletedExceptionally());
  }

  @Test
  void startConfirmationPreservesOlderIFrameDeadline() {
    session.onConnected();
    send();
    advance(14);
    session.startDataTransfer();
    confirm(UFunction.STARTDT_CON);

    advance(1);
    assertTimedOut();
  }

  @Test
  void acknowledgingOlderIFrameLeavesStartItsOwnDeadline() {
    session.onConnected();
    send();
    advance(14);
    CompletableFuture<Void> start = session.startDataTransfer().toCompletableFuture();
    acknowledge(1);

    advance(14);
    assertOpen();
    advance(1);
    assertTimedOut();
    assertTrue(start.isCompletedExceptionally());
  }

  @Test
  void stopConfirmationPreservesOlderIFrameDeadline() {
    start();
    send();
    advance(14);
    session.stopDataTransfer();
    confirm(UFunction.STOPDT_CON);

    advance(1);
    assertTimedOut();
  }

  @Test
  void acknowledgingOlderIFrameLeavesStopItsOwnDeadline() {
    start();
    send();
    advance(14);
    CompletableFuture<Void> stop = session.stopDataTransfer().toCompletableFuture();
    acknowledge(1);

    advance(14);
    assertOpen();
    advance(1);
    assertTimedOut();
    assertTrue(stop.isCompletedExceptionally());
  }

  @Test
  void dispatchedAcknowledgedTimerCannotCloseSessionWithNewerOutstandingFrame() {
    start();
    send();
    Runnable stale = scheduler.lastRunnableWithDelay(15_000);
    advance(5);
    send();
    acknowledge(1);

    stale.run();
    assertOpen();
    advance(14);
    assertOpen();
    advance(1);
    assertTimedOut();
  }

  @Test
  void acknowledgementAcrossSequenceWrapCancelsOnlyAcknowledgedDeadlines() {
    start();
    for (int i = 0; i < 32_767; i++) {
      send();
      acknowledge(i + 1);
    }
    send(); // N(S)=32767
    advance(5);
    send(); // N(S)=0
    advance(9);
    acknowledge(0); // Acknowledge N(S)=32767, leaving N(S)=0 outstanding.

    advance(5);
    assertOpen();
    advance(1);
    assertTimedOut();
  }

  @Test
  void connectionResetInvalidatesDispatchedIFrameDeadline() {
    start();
    send();
    Runnable stale = scheduler.lastRunnableWithDelay(15_000);
    advance(14);
    start();
    send();

    stale.run();
    advance(14);
    assertOpen();
    advance(1);
    assertTimedOut();
  }

  @Test
  void synchronousCloseDuringStartSendCancelsItsDeadline() {
    assertSynchronousCloseCancelsDeadline(UFunction.STARTDT_ACT);
  }

  @Test
  void synchronousCloseDuringStopSendCancelsItsDeadline() {
    assertSynchronousCloseCancelsDeadline(UFunction.STOPDT_ACT);
  }

  @Test
  void synchronousCloseDuringTestSendCancelsItsDeadline() {
    assertSynchronousCloseCancelsDeadline(UFunction.TESTFR_ACT);
  }

  private void assertSynchronousCloseCancelsDeadline(UFunction activation) {
    ApciSession[] holder = new ApciSession[1];
    ApciSession local =
        new ApciSession(
            ApciSession.Role.CLIENT,
            ApciSettings.defaults(),
            scheduler,
            apdu -> {
              if (apdu.control().equals(new ControlField.TypeU(activation))) {
                holder[0].close();
              }
            },
            events);
    holder[0] = local;
    local.onConnected();
    switch (activation) {
      case STARTDT_ACT -> local.startDataTransfer();
      case STOPDT_ACT -> {
        local.startDataTransfer();
        local.onApdu(new Apdu(new ControlField.TypeU(UFunction.STARTDT_CON), null));
        local.stopDataTransfer();
      }
      case TESTFR_ACT -> advance(20);
      default -> throw new AssertionError("not an activation: " + activation);
    }

    assertEquals(0, scheduler.pendingTaskCount());
    assertOpen();
  }

  @Test
  void closeAndReconnectInvalidateAllOldDeadlines() {
    start();
    advance(20);
    Runnable staleTest = scheduler.lastRunnableWithDelay(15_000);
    send();
    Runnable staleIFrame = scheduler.lastRunnableWithDelay(15_000);
    session.stopDataTransfer();
    Runnable staleStop = scheduler.lastRunnableWithDelay(15_000);

    session.close();
    assertEquals(0, scheduler.pendingTaskCount());
    start();
    staleTest.run();
    staleIFrame.run();
    staleStop.run();
    advance(15);
    assertOpen();
    assertEquals(1, scheduler.pendingTaskCount(), "only the new idle timer remains");
  }

  private void start() {
    session.onConnected();
    session.startDataTransfer();
    confirm(UFunction.STARTDT_CON);
  }

  private void send() {
    session.sendAsdu(
        new Asdu(
            AsduType.C_RD_NA_1,
            false,
            Cause.REQUEST,
            false,
            false,
            OriginatorAddress.none(),
            CommonAddress.of(1),
            List.of(new ReadCommand(InformationObjectAddress.of(1)))));
  }

  private void acknowledge(int nr) {
    session.onApdu(new Apdu(new ControlField.TypeS(nr), null));
  }

  private void confirm(UFunction function) {
    session.onApdu(new Apdu(new ControlField.TypeU(function), null));
  }

  private void advance(int seconds) {
    scheduler.advance(seconds, TimeUnit.SECONDS);
  }

  private void assertOpen() {
    assertNull(events.lastCloseCause());
  }

  private void assertTimedOut() {
    assertInstanceOf(ProtocolTimeoutException.class, events.lastCloseCause());
  }
}
