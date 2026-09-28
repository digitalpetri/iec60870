package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UnbalancedMasterTrafficFairnessTest {

  @Test
  void sustainedCommandsAndDuePollsBothProgressAndDeliverReadResponses() {
    var bus = new Bus(List.of(1));
    bus.master.startDataTransfer();
    bus.exchange(); // request status
    bus.exchange(); // reset

    for (int i = 0; i < 100; i++) {
      bus.master.sendAsdu(read(1, i));
    }
    for (int i = 0; i < 20; i++) {
      // A poll becomes due during every outstanding transaction. Both directions must progress.
      bus.scheduler.advance(5, TimeUnit.MILLISECONDS);
      bus.exchange();
    }

    assertEquals(10, bus.masterEvents.asdus().size(), "read replies must be polled under load");
    assertEquals(10, bus.slaveEvents.get(1).asdus().size(), "commands must keep progressing");
    assertEquals(0, bus.slaves.get(1).pendingSendCount());
    assertTrue(bus.master.pendingSendCount() > 0, "the command backlog never drained");
    for (int i = 0; i < 10; i++) {
      assertEquals(readResponse(read(1, i)), bus.masterEvents.asdus().get(i));
    }
  }

  @Test
  void queuedCommandsDoNotStarveAnotherSlavesBringUp() {
    var bus = new Bus(List.of(1, 2));
    for (int i = 0; i < 100; i++) {
      bus.master.sendAsdu(read(1, i));
    }
    bus.master.startDataTransfer();

    for (int i = 0; i < 6; i++) {
      bus.scheduler.advance(5, TimeUnit.MILLISECONDS);
      bus.exchange();
    }

    assertTrue(bus.slaves.get(2).isDataTransferStarted(), "slave 2 must be reset under load");
    assertFalse(bus.slaveEvents.get(1).asdus().isEmpty(), "slave 1 commands also progress");
    assertTrue(bus.master.pendingSendCount() > 0, "bring-up cannot wait for the queue to drain");
  }

  @Test
  void duePollClearsBackpressureWhileAnotherSlaveHasQueuedCommands() {
    var bus = new Bus(List.of(1, 2));
    bus.master.startDataTransfer();
    for (int i = 0; i < 4; i++) {
      bus.exchange();
    }

    bus.master.sendAsdu(read(1, 10));
    bus.slaves.get(1).onFrame(bus.toSlaves.removeFirst());
    bus.toMaster.removeFirst(); // replace the accepted command's ACK with DFC=1
    bus.master.onFrame(
        new Ft12Frame.FixedLength(LinkControlField.secondary(false, false, true, 0), 1));
    bus.master.sendAsdu(read(1, 11)); // held until slave 1 clears DFC
    for (int i = 0; i < 100; i++) {
      bus.master.sendAsdu(read(2, i));
    }
    bus.scheduler.advance(5, TimeUnit.MILLISECONDS);

    bus.exchange(); // command to slave 2
    Ft12Frame.FixedLength poll =
        assertInstanceOf(Ft12Frame.FixedLength.class, bus.toSlaves.getFirst());
    assertEquals(11, poll.control().functionCode());
    assertEquals(1, poll.linkAddress());
    bus.exchange(); // slave 1 reports DFC=0
    bus.exchange(); // the held slave-1 command is now deliverable

    assertEquals(List.of(read(1, 10), read(1, 11)), bus.slaveEvents.get(1).asdus());
    assertTrue(bus.master.pendingSendCount() > 0);
  }

  private static Asdu read(int address, int ioa) {
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(address),
        List.of(new ReadCommand(InformationObjectAddress.of(ioa))));
  }

  private static Asdu readResponse(Asdu request) {
    return new Asdu(
        AsduType.M_SP_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        request.originatorAddress(),
        request.commonAddress(),
        List.of(
            new SinglePointInformation(
                request.objects().get(0).address(),
                true,
                new Qds(false, false, false, false, false))));
  }

  /**
   * Exchanges queued frames only after callbacks return, matching the engine's threading contract.
   */
  private static final class Bus {
    private final ManualScheduler scheduler = new ManualScheduler();
    private final ArrayDeque<Ft12Frame> toSlaves = new ArrayDeque<>();
    private final ArrayDeque<Ft12Frame> toMaster = new ArrayDeque<>();
    private final RecordingEvents masterEvents = new RecordingEvents();
    private final Map<Integer, RecordingEvents> slaveEvents = new HashMap<>();
    private final Map<Integer, UnbalancedSlaveEngine> slaves = new HashMap<>();
    private final UnbalancedMasterEngine master;

    private Bus(List<Integer> addresses) {
      master =
          new UnbalancedMasterEngine(
              LinkSettings.unbalanced()
                  .slaveAddresses(addresses)
                  .pollInterval(Duration.ofMillis(5))
                  .build(),
              scheduler,
              toSlaves::addLast,
              masterEvents,
              0,
              OutboundQueuePolicy.DROP_OLDEST);
      for (int address : addresses) {
        var events = new RecordingEvents();
        slaveEvents.put(address, events);
        var slave =
            new UnbalancedSlaveEngine(
                LinkSettings.unbalanced().linkAddress(address).build(),
                scheduler,
                toMaster::addLast,
                events,
                0,
                OutboundQueuePolicy.DROP_OLDEST);
        slaves.put(address, slave);
        slave.onConnected();
      }
      master.onConnected();
    }

    private void exchange() {
      assertEquals(1, toSlaves.size(), "the master must keep one transaction outstanding");
      Ft12Frame frame = toSlaves.removeFirst();
      int address =
          frame instanceof Ft12Frame.Variable variable
              ? variable.linkAddress()
              : assertInstanceOf(Ft12Frame.FixedLength.class, frame).linkAddress();
      UnbalancedSlaveEngine slave = slaves.get(address);
      RecordingEvents events = slaveEvents.get(address);
      int received = events.asdus().size();
      slave.onFrame(frame);
      for (int i = received; i < events.asdus().size(); i++) {
        slave.sendAsdu(readResponse(events.asdus().get(i)));
      }
      assertEquals(1, toMaster.size());
      master.onFrame(toMaster.removeFirst());
    }
  }
}
