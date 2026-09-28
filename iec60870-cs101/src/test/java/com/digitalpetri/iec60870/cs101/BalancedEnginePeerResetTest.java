package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Tests the independent primary and secondary processes required by IEC 101 section 6.2.1.2. */
class BalancedEnginePeerResetTest {

  @Test
  void peerResetPreservesOutboundFcbAndDeliversDistinctAcknowledgedAsdus() {
    for (boolean singleCharAck : List.of(false, true)) {
      var pair = new EnginePair(singleCharAck);
      Asdu first = readAsdu(1);
      Asdu second = readAsdu(2);

      pair.local.sendAsdu(first);
      Ft12Frame.Variable firstFrame = pair.deliverLocalData();
      assertTrue(firstFrame.control().fcb());
      pair.local.onFrame(pair.peerOutput.removeFirst());

      // The peer resets only its primary direction. Its secondary keeps the FCB/cache for our data.
      pair.resetLocalSecondary();
      pair.local.sendAsdu(second);
      Ft12Frame.Variable secondFrame = pair.deliverLocalData();
      pair.local.onFrame(pair.peerOutput.removeFirst());

      assertEquals(List.of(first, second), pair.peerEvents.asdus());
      assertFalse(secondFrame.control().fcb(), "a peer reset must not restart our primary FCB");
      pair.localScheduler.advance(4000, TimeUnit.MILLISECONDS);
      assertTrue(pair.localOutput.isEmpty(), "both data frames were acknowledged");
      assertEquals(0, pair.localEvents.closedCount());
    }
  }

  @Test
  void peerResetPreservesPendingDataAndItsRetryDeadline() {
    for (boolean singleCharAck : List.of(false, true)) {
      var pair = new EnginePair(singleCharAck);
      Asdu first = readAsdu(1);
      pair.local.sendAsdu(first);
      pair.deliverLocalData();
      pair.local.onFrame(pair.peerOutput.removeFirst());

      Asdu inFlight = readAsdu(2);
      Asdu queued = readAsdu(3);
      pair.local.sendAsdu(inFlight);
      Ft12Frame.Variable original = pair.deliverLocalData();
      assertFalse(original.control().fcb());
      pair.peerOutput.removeFirst(); // Lose the data ACK, leaving the transaction pending.
      pair.local.sendAsdu(queued);
      pair.localScheduler.advance(50, TimeUnit.MILLISECONDS);

      pair.resetLocalSecondary();
      assertTrue(pair.localOutput.isEmpty(), "the reset must not release the primary window");
      assertEquals(1, pair.local.pendingSendCount());

      pair.localScheduler.advance(149, TimeUnit.MILLISECONDS);
      assertTrue(pair.localOutput.isEmpty());
      pair.localScheduler.advance(1, TimeUnit.MILLISECONDS);
      Ft12Frame.Variable retry = pair.deliverLocalData();
      assertEquals(original, retry, "retry the same ASDU and FCB at the original deadline");
      assertEquals(List.of(first, inFlight), pair.peerEvents.asdus(), "no duplicate delivery");

      pair.local.onFrame(pair.peerOutput.removeFirst());
      Ft12Frame.Variable resumed = pair.deliverLocalData();
      assertEquals(queued, resumed.asdu());
      assertTrue(resumed.control().fcb());
      pair.local.onFrame(pair.peerOutput.removeFirst());
      assertEquals(List.of(first, inFlight, queued), pair.peerEvents.asdus());
      assertEquals(0, pair.local.pendingSendCount());
      pair.localScheduler.advance(4000, TimeUnit.MILLISECONDS);
      assertTrue(pair.localOutput.isEmpty(), "the confirmed retry timer was cancelled");
      assertEquals(0, pair.localEvents.closedCount());
    }
  }

  @Test
  void restartedPeerCanReceiveAnUnacknowledgedAsduAgain() {
    for (boolean singleCharAck : List.of(false, true)) {
      var pair = new EnginePair(singleCharAck);
      Asdu asdu = readAsdu(7);
      pair.local.sendAsdu(asdu);
      Ft12Frame.Variable original = pair.deliverLocalData();
      assertEquals(List.of(asdu), pair.peerEvents.asdus());
      pair.peerOutput.removeFirst(); // Lose the ACK after the peer delivered the ASDU.
      pair.localScheduler.advance(50, TimeUnit.MILLISECONDS);

      // A serial peer can restart while the local SERVER's port and engine remain open.
      pair.peer.close();
      pair.peer.onConnected();
      pair.bringUpPeer();
      assertTrue(pair.localOutput.isEmpty());
      assertEquals(List.of(asdu), pair.peerEvents.asdus());

      pair.localScheduler.advance(149, TimeUnit.MILLISECONDS);
      assertTrue(pair.localOutput.isEmpty());
      pair.localScheduler.advance(1, TimeUnit.MILLISECONDS);
      assertEquals(original, pair.deliverLocalData());
      assertEquals(
          List.of(asdu, asdu),
          pair.peerEvents.asdus(),
          "the restarted secondary lost its cached ACK and delivers the retry again");

      pair.local.onFrame(pair.peerOutput.removeFirst());
      pair.localScheduler.advance(4000, TimeUnit.MILLISECONDS);
      assertTrue(pair.localOutput.isEmpty(), "the retry was acknowledged");
      assertEquals(0, pair.localEvents.closedCount());
    }
  }

  @Test
  void peerResetPreservesPendingKeepalive() {
    for (boolean singleCharAck : List.of(false, true)) {
      var pair = new EnginePair(singleCharAck);
      pair.localScheduler.advance(5000, TimeUnit.MILLISECONDS);
      Ft12Frame.FixedLength keepalive =
          assertInstanceOf(Ft12Frame.FixedLength.class, pair.localOutput.removeFirst());
      assertEquals(9, keepalive.control().functionCode());
      assertTrue(keepalive.control().prm());
      Asdu queued = readAsdu(1);
      pair.local.sendAsdu(queued);

      pair.resetLocalSecondary();
      assertTrue(pair.localOutput.isEmpty(), "the status response still owns the primary window");
      assertEquals(1, pair.local.pendingSendCount());

      pair.localScheduler.advance(200, TimeUnit.MILLISECONDS);
      assertEquals(keepalive, pair.localOutput.removeFirst());
      pair.peer.onFrame(keepalive);
      pair.local.onFrame(pair.peerOutput.removeFirst());
      assertEquals(queued, pair.deliverLocalData().asdu());
      pair.local.onFrame(pair.peerOutput.removeFirst());
      assertEquals(List.of(queued), pair.peerEvents.asdus());
      assertEquals(0, pair.localEvents.closedCount());
    }
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

  private static final class EnginePair {
    private final ManualScheduler localScheduler = new ManualScheduler();
    private final ArrayDeque<Ft12Frame> localOutput = new ArrayDeque<>();
    private final ArrayDeque<Ft12Frame> peerOutput = new ArrayDeque<>();
    private final RecordingEvents localEvents = new RecordingEvents();
    private final RecordingEvents peerEvents = new RecordingEvents();
    private final BalancedEngine local;
    private final BalancedEngine peer;

    EnginePair(boolean singleCharAck) {
      LinkSettings settings = LinkSettings.balanced().useSingleCharAck(singleCharAck).build();
      local =
          new BalancedEngine(
              Ft12LinkLayer.Role.SERVER,
              settings,
              localScheduler,
              localOutput::addLast,
              localEvents);
      peer =
          new BalancedEngine(
              Ft12LinkLayer.Role.CLIENT,
              settings,
              new ManualScheduler(),
              peerOutput::addLast,
              peerEvents);
      local.onConnected();
      peer.onConnected();
      bringUpPeer();
    }

    void bringUpPeer() {
      peer.startDataTransfer();
      // Relay outside output callbacks so neither engine is re-entered while it holds its lock.
      local.onFrame(peerOutput.removeFirst()); // FC9 -> FC11
      peer.onFrame(localOutput.removeFirst()); // FC11 -> FC0
      local.onFrame(peerOutput.removeFirst()); // FC0 -> ACK
      peer.onFrame(localOutput.removeFirst()); // ACK completes bring-up
      assertTrue(local.isDataTransferStarted());
      assertTrue(peer.isDataTransferStarted());
    }

    void resetLocalSecondary() {
      // Inject the wire request without onConnected(): the peer's secondary has not restarted.
      local.onFrame(new Ft12Frame.FixedLength(LinkControlField.primary(true, false, false, 0), 1));
      Ft12Frame ack = localOutput.removeFirst();
      if (!(ack instanceof Ft12Frame.SingleChar)) {
        Ft12Frame.FixedLength fixedAck = assertInstanceOf(Ft12Frame.FixedLength.class, ack);
        assertFalse(fixedAck.control().prm());
        assertEquals(0, fixedAck.control().functionCode());
      }
      assertEquals(List.of(true), localEvents.dataTransferChanges());
    }

    Ft12Frame.Variable deliverLocalData() {
      Ft12Frame.Variable frame =
          assertInstanceOf(Ft12Frame.Variable.class, localOutput.removeFirst());
      peer.onFrame(frame);
      return frame;
    }
  }
}
