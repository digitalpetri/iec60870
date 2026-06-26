package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.testsupport.ManualScheduler;
import com.digitalpetri.iec60870.testsupport.RecordingEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link UnbalancedMasterEngine}'s handling of a command whose common address
 * maps to no usable secondary.
 *
 * <p>Because the unbalanced master maps each command's common address onto a configured secondary's
 * link address (the frozen façade mapping), a command for an unconfigured address — or for a
 * secondary that has been degraded to the error state — can never be put on the shared bus. Rather
 * than discarding it silently (which leaves the high-level facade blocked until a generic timeout),
 * the master answers it locally with the negative confirmation a real secondary returns for a
 * command addressed to a common address it does not serve: the same ASDU echoed back with the
 * positive/negative bit set and cause {@link Cause#UNKNOWN_COMMON_ADDRESS}. These tests drive the
 * engine through a {@link ManualScheduler} virtual clock and assert the rejection is delivered
 * <em>synchronously</em> from {@link UnbalancedMasterEngine#sendAsdu(Asdu)} — with no time elapsing
 * — so the facade correlates it promptly instead of timing out.
 */
class UnbalancedMasterUnknownAddressTest {

  /** Two configured secondaries (link addresses 1 and 2) polled on a {@code 1000 ms} cadence. */
  private static final LinkSettings SETTINGS =
      LinkSettings.unbalanced()
          .slaveAddresses(List.of(1, 2))
          .pollInterval(Duration.ofMillis(1000))
          .build();

  private static final long CONFIRM_MILLIS = 200;
  private static final long REPEAT_MILLIS = 1000;

  // Secondary (PRM=0) function codes a slave returns, used to drive bring-up.
  private static final int FC_ACK = 0;
  private static final int FC_STATUS_OF_LINK = 11;

  private static final int SLAVE_1 = 1;
  private static final int SLAVE_2 = 2;
  private static final int UNKNOWN_CA = 99;

  private ManualScheduler scheduler;
  private RecordingOutput output;
  private RecordingEvents events;

  @BeforeEach
  void setUp() {
    scheduler = new ManualScheduler();
    output = new RecordingOutput();
    events = new RecordingEvents();
  }

  private UnbalancedMasterEngine newMaster(LinkSettings settings) {
    return new UnbalancedMasterEngine(
        settings, scheduler, output, events, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  @Test
  void commandToUnknownCommonAddressIsRejectedPromptlyWithNegativeConfirmation() {
    UnbalancedMasterEngine master = newMaster(SETTINGS);
    bringUpAll(master);

    // A command addressed to a common address that matches no configured secondary cannot reach the
    // bus. It must be answered at once, not silently dropped to time out later.
    Asdu command = command(UNKNOWN_CA, 5);
    master.sendAsdu(command);

    // Nothing was put on the bus, and the queue is drained at once (no advance of the clock).
    assertEquals(0, output.frames().size(), "an undeliverable command is not framed");
    assertEquals(0, master.pendingSendCount(), "the command is removed from the queue");

    // The facade is handed a negative confirmation echoing the command, so it correlates the
    // rejection to the waiting operation and fails promptly with a clear cause.
    assertEquals(1, events.asdus().size(), "the rejection is delivered through onAsdu");
    Asdu rejection = events.asdus().get(0);
    assertTrue(rejection.negative(), "the rejection sets the P/N bit");
    assertEquals(Cause.UNKNOWN_COMMON_ADDRESS, rejection.cause());
    assertEquals(command.type(), rejection.type(), "echoes the command type");
    assertEquals(command.commonAddress(), rejection.commonAddress(), "echoes the common address");
    assertEquals(command.objects(), rejection.objects(), "echoes the addressed object(s)");

    // The bus and the session are untouched: one bad address must not close the session.
    assertEquals(0, events.closedCount(), "rejecting a command must not close the session");
    assertTrue(master.isDataTransferStarted(), "data transfer stays started");
  }

  @Test
  void commandToDegradedSlaveIsRejectedPromptlyWithNegativeConfirmation() {
    // A long poll interval keeps the poll timer out of the way; a single retry shortens the run.
    LinkSettings settings =
        LinkSettings.unbalanced()
            .slaveAddresses(List.of(SLAVE_1, SLAVE_2))
            .pollInterval(Duration.ofMillis(100_000))
            .maxRetries(1)
            .build();
    UnbalancedMasterEngine master = newMaster(settings);
    bringUpAll(master);

    // Degrade slave 1: user data to it goes unacknowledged through its single retry, so the master
    // marks that slave (and only that slave) ERROR while keeping the session open.
    master.sendAsdu(command(SLAVE_1, 10));
    scheduler.advance(CONFIRM_MILLIS, TimeUnit.MILLISECONDS); // first retransmission
    scheduler.advance(REPEAT_MILLIS, TimeUnit.MILLISECONDS); // exhaust the retry -> ERROR
    assertEquals(0, events.closedCount(), "a per-slave timeout must not close the session");
    assertTrue(events.asdus().isEmpty(), "degrading a slave delivers no application ASDU");
    output.clear();

    // A fresh command for the now-degraded slave is undeliverable and must be rejected promptly,
    // exactly as for an unconfigured address, rather than dropped to time out.
    Asdu command = command(SLAVE_1, 11);
    master.sendAsdu(command);

    assertEquals(0, output.frames().size(), "a command for a degraded slave is not framed");
    assertEquals(1, events.asdus().size(), "the rejection is delivered through onAsdu");
    Asdu rejection = events.asdus().get(0);
    assertTrue(rejection.negative());
    assertEquals(Cause.UNKNOWN_COMMON_ADDRESS, rejection.cause());
    assertEquals(command.type(), rejection.type());
    assertEquals(command.commonAddress(), rejection.commonAddress());
    assertEquals(command.objects(), rejection.objects());
    assertEquals(0, events.closedCount());
  }

  // --- Fixtures --------------------------------------------------------------------------------

  /**
   * Drives the master through {@code onConnected} and the full per-slave bring-up of both
   * configured secondaries, leaving both available and the captured frames cleared.
   *
   * @param master the engine to bring up.
   */
  private void bringUpAll(UnbalancedMasterEngine master) {
    master.onConnected();
    master.startDataTransfer();
    master.onFrame(secondaryFixed(FC_STATUS_OF_LINK, SLAVE_1));
    master.onFrame(secondaryFixed(FC_ACK, SLAVE_1));
    master.onFrame(secondaryFixed(FC_STATUS_OF_LINK, SLAVE_2));
    master.onFrame(secondaryFixed(FC_ACK, SLAVE_2));
    output.clear();
  }

  /**
   * Builds a secondary (response) fixed frame from a slave; DIR is not significant to dispatch and
   * is left {@code false} (unbalanced secondary frames carry RES).
   *
   * @param functionCode the secondary function code.
   * @param linkAddress the responding slave's link address.
   * @return the inbound fixed-length frame.
   */
  private static Ft12Frame secondaryFixed(int functionCode, int linkAddress) {
    return new Ft12Frame.FixedLength(
        LinkControlField.secondary(false, false, false, functionCode), linkAddress);
  }

  /**
   * Builds a minimal read-command ASDU stamped with the given common address and information-object
   * address.
   *
   * @param commonAddress the common address selecting the target slave.
   * @param ioa the information-object address.
   * @return the ASDU.
   */
  private static Asdu command(int commonAddress, int ioa) {
    InformationObject object = new ReadCommand(InformationObjectAddress.of(ioa));
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(commonAddress),
        List.of(object));
  }

  /** Records every {@link Ft12Frame} the engine emits, in send order. */
  private static final class RecordingOutput implements Ft12LinkLayer.Output {

    private final List<Ft12Frame> frames = new ArrayList<>();

    @Override
    public void send(Ft12Frame frame) {
      frames.add(frame);
    }

    List<Ft12Frame> frames() {
      return frames;
    }

    void clear() {
      frames.clear();
    }
  }
}
