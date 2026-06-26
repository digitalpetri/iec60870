package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import com.digitalpetri.iec60870.testsupport.ManualScheduler;
import com.digitalpetri.iec60870.testsupport.RecordingEvents;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end multi-drop integration test for the unbalanced FT1.2 link layer: one real {@link
 * Ft12LinkLayer.Role#CLIENT CLIENT} master polling two real {@link Ft12LinkLayer.Role#SERVER
 * SERVER} slaves over a single shared frame bus, wired exclusively through the public {@link
 * Ft12LinkLayer} dispatcher so the {@code (role, mode)} selection of the {@code
 * UnbalancedMasterEngine} and {@code UnbalancedSlaveEngine} is exercised exactly as a production
 * assembly would drive it.
 *
 * <p><b>The bus.</b> Each station's {@link Ft12LinkLayer.Output} hands every emitted {@link
 * Ft12Frame} to a tiny in-test relay. Rather than deliver re-entrantly (a slave responding inside
 * the master's {@code output.send} call, under the master lock), the relay <em>posts</em> each
 * delivery to a FIFO queue; {@link #pumpBus()} drains that queue on the test thread, so every
 * {@code onFrame} runs outside any engine lock and a single primary frame can cascade through the
 * whole bring-up. The relay routes by sender: a master-emitted (primary) frame goes to the slave
 * whose {@linkplain Ft12Frame.FixedLength#linkAddress() link address} matches (a broadcast goes to
 * every slave), and a slave-emitted (secondary) frame returns to the single master — including the
 * {@code 0xE5} {@link Ft12Frame.SingleChar} acknowledgement, which carries no link address and is
 * emitted only by the slave the master just polled.
 *
 * <p><b>Timing.</b> A shared {@link ManualScheduler} virtual clock drives the master's poll and
 * confirm timers; the queue-based bus keeps routing fully synchronous, so the {@code
 * ManualScheduler} stays deterministic without a wall-clock poll loop.
 *
 * <p><b>Scope.</b> This is a frame-level test fixture, not a wire-level one: there is no octet
 * framing, no half-duplex line timing, and no lost-frame injection (the FT1.2 octet codec and
 * retransmission/timeout paths are covered by the dedicated engine and framer unit tests). The ACD
 * escalation is exercised by buffering <em>two</em> class-1 events on a slave — a single buffered
 * event is answered directly by the slave's class-2 fallback and would not assert the access-demand
 * bit, so it would never escalate to a class-1 poll.
 */
class UnbalancedMultiDropTest {

  /** The configured secondary link addresses; also used as the slaves' common addresses. */
  private static final int SLAVE_1_ADDRESS = 1;

  private static final int SLAVE_2_ADDRESS = 2;

  /** The one-octet all-secondaries broadcast address used by the unbalanced master. */
  private static final int BROADCAST_ADDRESS = 255;

  /** The class-2 poll cadence configured on the master. */
  private static final long POLL_MILLIS = 500;

  /** The master's default confirm timeout (see {@link LinkSettings#unbalanced()}). */
  private static final long CONFIRM_MILLIS = 200;

  // FT1.2 primary function codes asserted on master-emitted frames.
  private static final int FC_USER_DATA = 3;
  private static final int FC_REQUEST_CLASS_1 = 10;
  private static final int FC_REQUEST_CLASS_2 = 11;

  /** A guard on {@link #pumpBus()} so a routing bug surfaces as a failure, not a hang. */
  private static final int MAX_BUS_DELIVERIES = 1000;

  private ManualScheduler scheduler;
  private final ArrayDeque<Runnable> busQueue = new ArrayDeque<>();

  private StationOutput masterOut;
  private StationOutput slave1Out;
  private StationOutput slave2Out;

  private RecordingEvents masterEvents;
  private RecordingEvents slave1Events;
  private RecordingEvents slave2Events;

  private Ft12LinkLayer master;
  private Ft12LinkLayer slave1;
  private Ft12LinkLayer slave2;

  @BeforeEach
  void setUp() {
    scheduler = new ManualScheduler();
    busQueue.clear();

    masterOut = new StationOutput(true);
    slave1Out = new StationOutput(false);
    slave2Out = new StationOutput(false);

    masterEvents = new RecordingEvents();
    slave1Events = new RecordingEvents();
    slave2Events = new RecordingEvents();

    LinkSettings masterSettings =
        LinkSettings.unbalanced()
            .slaveAddresses(List.of(SLAVE_1_ADDRESS, SLAVE_2_ADDRESS))
            .pollInterval(Duration.ofMillis(POLL_MILLIS))
            .build();
    LinkSettings slave1Settings = LinkSettings.unbalanced().linkAddress(SLAVE_1_ADDRESS).build();
    LinkSettings slave2Settings = LinkSettings.unbalanced().linkAddress(SLAVE_2_ADDRESS).build();

    master =
        new Ft12LinkLayer(
            Ft12LinkLayer.Role.CLIENT, masterSettings, scheduler, masterOut, masterEvents);
    slave1 =
        new Ft12LinkLayer(
            Ft12LinkLayer.Role.SERVER, slave1Settings, scheduler, slave1Out, slave1Events);
    slave2 =
        new Ft12LinkLayer(
            Ft12LinkLayer.Role.SERVER, slave2Settings, scheduler, slave2Out, slave2Events);
  }

  // --- Bring-up --------------------------------------------------------------------------------

  @Test
  void bothSlavesReachDataTransferStarted() {
    bringUp();

    // The unbalanced master marks itself started as soon as the poller is armed and reports no
    // data-transfer event of its own; each slave reports onDataTransferStateChanged(true) exactly
    // once, when the master resets that slave's link.
    assertTrue(master.isDataTransferStarted(), "the master poller is running after bring-up");
    assertTrue(
        masterEvents.dataTransferChanges().isEmpty(),
        "the unbalanced master emits no data-transfer event of its own");

    assertTrue(slave1.isDataTransferStarted());
    assertTrue(slave2.isDataTransferStarted());
    assertEquals(List.of(Boolean.TRUE), slave1Events.dataTransferChanges());
    assertEquals(List.of(Boolean.TRUE), slave2Events.dataTransferChanges());
  }

  // --- Class-2 polling -------------------------------------------------------------------------

  @Test
  void pollTimerPollsBothSlaves() {
    bringUp();
    clearOutputs();

    // Each poll tick polls the next available slave round robin, so two ticks cover both slaves.
    scheduler.advance(POLL_MILLIS, TimeUnit.MILLISECONDS);
    pumpBus();
    scheduler.advance(POLL_MILLIS, TimeUnit.MILLISECONDS);
    pumpBus();

    assertTrue(
        masterSentPrimaryFixed(FC_REQUEST_CLASS_2, SLAVE_1_ADDRESS),
        "the master must issue a class-2 poll to slave 1");
    assertTrue(
        masterSentPrimaryFixed(FC_REQUEST_CLASS_2, SLAVE_2_ADDRESS),
        "the master must issue a class-2 poll to slave 2");
  }

  // --- Spontaneous data + ACD escalation -------------------------------------------------------

  @Test
  void slaveSpontaneousSurfacesOnlyOnSlaveOnePollWithAcdEscalation() {
    bringUp();
    clearOutputs();

    // Two spontaneous (class-1) events buffered on slave 1. The class-2 poll answers with the first
    // and asserts the access-demand bit (a class-1 event still pending), which escalates the master
    // to a class-1 poll (FC10) that drains the second event.
    Asdu first = spontaneous(100, SLAVE_1_ADDRESS);
    Asdu second = spontaneous(101, SLAVE_1_ADDRESS);
    slave1.sendAsdu(first);
    slave1.sendAsdu(second);
    assertTrue(
        masterEvents.asdus().isEmpty(), "a slave buffers spontaneous data until it is polled");

    // The round-robin cursor starts at the first configured slave, so the first tick polls slave 1.
    scheduler.advance(POLL_MILLIS, TimeUnit.MILLISECONDS);
    pumpBus();

    assertEquals(
        List.of(first, second),
        masterEvents.asdus(),
        "both class-1 events must surface at the master on slave 1's poll");
    assertTrue(
        masterSentPrimaryFixed(FC_REQUEST_CLASS_1, SLAVE_1_ADDRESS),
        "ACD on the class-2 response must escalate the master to a class-1 poll of slave 1");

    // The next tick polls slave 2, which has nothing buffered: no further ASDU surfaces.
    scheduler.advance(POLL_MILLIS, TimeUnit.MILLISECONDS);
    pumpBus();

    assertEquals(
        List.of(first, second),
        masterEvents.asdus(),
        "polling the other slave must surface nothing more");
    assertTrue(slave2Events.asdus().isEmpty(), "slave 2 had no data to deliver");
  }

  // --- Individually addressed command ----------------------------------------------------------

  @Test
  void masterCommandIsDeliveredToAddressedSlaveAndAcked() {
    bringUp();
    clearOutputs();

    // A command whose common address is slave 2's link address is sent as send/confirm user data
    // (FC3) to slave 2 only, and slave 2 acknowledges it.
    Asdu command = singleCommand(200, SLAVE_2_ADDRESS);
    master.sendAsdu(command);
    pumpBus();

    assertEquals(
        List.of(command),
        slave2Events.asdus(),
        "the command must be delivered to the addressed slave");
    assertTrue(
        slave1Events.asdus().isEmpty(), "the unaddressed slave must not receive the command");
    assertEquals(
        1,
        countMasterUserDataTo(SLAVE_2_ADDRESS),
        "exactly one user-data frame is sent to slave 2");

    assertEquals(1, slave2Out.frames().size(), "slave 2 answers the command with a single ack");
    assertInstanceOf(
        Ft12Frame.SingleChar.class,
        slave2Out.frames().get(0),
        "the default acknowledgement is the 0xE5 single-character frame");

    // The acknowledgement freed the bus: advancing past the confirm timeout retransmits nothing.
    scheduler.advance(CONFIRM_MILLIS + 50, TimeUnit.MILLISECONDS);
    pumpBus();
    assertEquals(
        1,
        countMasterUserDataTo(SLAVE_2_ADDRESS),
        "an acknowledged command must not be retransmitted");
  }

  // --- Fixtures --------------------------------------------------------------------------------

  /** Connects all three stations and drives the master through bring-up of both slaves. */
  private void bringUp() {
    master.onConnected();
    slave1.onConnected();
    slave2.onConnected();
    master.startDataTransfer();
    pumpBus();
  }

  /** Drains every queued frame delivery (and any it triggers) until the bus is quiet. */
  private void pumpBus() {
    int delivered = 0;
    while (!busQueue.isEmpty()) {
      if (++delivered > MAX_BUS_DELIVERIES) {
        throw new AssertionError(
            "the frame bus did not settle after "
                + MAX_BUS_DELIVERIES
                + " deliveries; possible frame storm");
      }
      busQueue.removeFirst().run();
    }
  }

  /**
   * Routes one emitted frame on the shared bus.
   *
   * @param frame the emitted frame.
   * @param fromMaster whether the master (primary) emitted the frame.
   */
  private void route(Ft12Frame frame, boolean fromMaster) {
    if (!fromMaster) {
      // A secondary (slave-emitted) frame — including a 0xE5 single-character ack, which carries no
      // link address and is emitted only by the slave the master just polled — returns to the
      // single master.
      master.onFrame(frame);
      return;
    }
    // A primary (master-emitted) frame is addressed: deliver it to the matching slave, or to every
    // slave for a broadcast.
    int address = linkAddressOf(frame);
    if (address == BROADCAST_ADDRESS) {
      slave1.onFrame(frame);
      slave2.onFrame(frame);
    } else if (address == SLAVE_1_ADDRESS) {
      slave1.onFrame(frame);
    } else if (address == SLAVE_2_ADDRESS) {
      slave2.onFrame(frame);
    }
    // An address with no configured station is dropped, mirroring a bus with no such slave.
  }

  private static int linkAddressOf(Ft12Frame frame) {
    if (frame instanceof Ft12Frame.FixedLength fixed) {
      return fixed.linkAddress();
    }
    if (frame instanceof Ft12Frame.Variable variable) {
      return variable.linkAddress();
    }
    return -1; // a single-character frame carries no link address
  }

  private void clearOutputs() {
    masterOut.clear();
    slave1Out.clear();
    slave2Out.clear();
  }

  /**
   * Reports whether the master emitted a primary fixed-length frame with the given code/address.
   */
  private boolean masterSentPrimaryFixed(int functionCode, int address) {
    return masterOut.frames().stream()
        .anyMatch(
            frame ->
                frame instanceof Ft12Frame.FixedLength fixed
                    && fixed.control().prm()
                    && fixed.control().functionCode() == functionCode
                    && fixed.linkAddress() == address);
  }

  /** Counts the primary user-data (FC3) frames the master sent to the given slave address. */
  private long countMasterUserDataTo(int address) {
    return masterOut.frames().stream()
        .filter(
            frame ->
                frame instanceof Ft12Frame.Variable variable
                    && variable.control().prm()
                    && variable.control().functionCode() == FC_USER_DATA
                    && variable.linkAddress() == address)
        .count();
  }

  private static Asdu spontaneous(int ioa, int commonAddress) {
    InformationObject object =
        new SinglePointInformation(
            InformationObjectAddress.of(ioa), true, new Qds(false, false, false, false, false));
    return new Asdu(
        AsduType.M_SP_NA_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(commonAddress),
        List.of(object));
  }

  private static Asdu singleCommand(int ioa, int commonAddress) {
    InformationObject object =
        new SingleCommand(InformationObjectAddress.of(ioa), true, new QualifierOfCommand(0, false));
    return new Asdu(
        AsduType.C_SC_NA_1,
        false,
        Cause.ACTIVATION,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(commonAddress),
        List.of(object));
  }

  /**
   * A station's frame sink: records every emitted frame and posts it to the shared bus for routing.
   */
  private final class StationOutput implements Ft12LinkLayer.Output {

    private final boolean fromMaster;
    private final List<Ft12Frame> frames = new ArrayList<>();

    StationOutput(boolean fromMaster) {
      this.fromMaster = fromMaster;
    }

    @Override
    public void send(Ft12Frame frame) {
      // Record, then post (do not deliver inline): the engine is invoking this under its own lock,
      // so re-entrant delivery would nest a peer's onFrame under that lock. pumpBus() delivers
      // later on the test thread, outside any engine lock.
      frames.add(frame);
      busQueue.addLast(() -> route(frame, fromMaster));
    }

    List<Ft12Frame> frames() {
      return frames;
    }

    void clear() {
      frames.clear();
    }
  }
}
