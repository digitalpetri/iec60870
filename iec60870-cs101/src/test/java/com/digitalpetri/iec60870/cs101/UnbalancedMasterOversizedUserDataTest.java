package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import com.digitalpetri.iec60870.testsupport.ManualScheduler;
import com.digitalpetri.iec60870.testsupport.RecordingEvents;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression test for {@link UnbalancedMasterEngine}'s handling of a command that cannot be framed
 * into a single FT1.2 variable frame.
 *
 * <p>An FT1.2 variable frame may carry at most {@code 255} user-data octets, so an oversized ASDU
 * makes the framer behind {@link Ft12LinkLayer.Output#send(Ft12Frame)} throw. The throw happens
 * <em>inside</em> {@link UnbalancedMasterEngine}'s {@code sendUserData}, after the
 * single-transaction window has been opened (the slave's {@code pending} descriptor is set) but
 * before the confirm timer is armed. If the throw escaped, that slave's stop-and-wait path would be
 * wedged permanently: {@code pending} would stay set with no timer ever clearing it, so no further
 * transaction to that slave could be issued. These tests prove the engine instead rolls the
 * transaction back, fails the offending command locally with a negative confirmation, and keeps the
 * slave's path live.
 *
 * <p>The engine is driven through a {@link ManualScheduler} virtual clock and a {@link
 * FramingOutput} that runs every emitted {@link Ft12Frame} through {@link Ft12Framer} exactly as
 * the real {@code Cs101Binding} does, so an over-length user-data frame triggers the genuine {@link
 * IllegalArgumentException} rather than a stand-in.
 */
class UnbalancedMasterOversizedUserDataTest {

  /** Two configured secondaries (link addresses 1 and 2) polled on a {@code 1000 ms} cadence. */
  private static final LinkSettings SETTINGS =
      LinkSettings.unbalanced()
          .slaveAddresses(List.of(1, 2))
          .pollInterval(Duration.ofMillis(1000))
          .build();

  /**
   * The typical IEC 60870-5-101 field widths; a 1-octet link address matches the unbalanced bus.
   */
  private static final ProtocolProfile PROFILE = ProtocolProfile.iec101Default();

  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;
  private static final int LINK_ADDRESS_LENGTH = SETTINGS.linkAddressLength();

  // Primary (PRM=1) function codes the master sends.
  private static final int FC_USER_DATA = 3;

  // Secondary (PRM=0) function codes a slave returns, used to drive bring-up.
  private static final int FC_ACK = 0;
  private static final int FC_STATUS_OF_LINK = 11;

  private static final int SLAVE_1 = 1;

  /**
   * A non-sequence single-point-information ASDU of this many objects encodes to well over the
   * {@code 255}-octet FT1.2 user-data limit (header plus {@code 3} octets per object), so it cannot
   * be framed.
   */
  private static final int OVERSIZED_OBJECT_COUNT = 100;

  private ManualScheduler scheduler;
  private FramingOutput output;
  private RecordingEvents events;

  @BeforeEach
  void setUp() {
    scheduler = new ManualScheduler();
    output = new FramingOutput();
    events = new RecordingEvents();
  }

  private UnbalancedMasterEngine newMaster() {
    return new UnbalancedMasterEngine(
        SETTINGS, scheduler, output, events, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  @Test
  void oversizedUserDataFailsTheCommandAndDoesNotWedgeTheSlavePath() {
    UnbalancedMasterEngine master = newMaster();
    bringUpAll(master);

    // Publishing one ASDU too large to frame in a single FT1.2 variable frame makes output.send
    // throw from Ft12Framer (user data over 255 octets). The engine must roll back the transaction
    // it just opened rather than leaving slave 1 wedged with a pending transaction and no timer.
    Asdu oversized = singlePointAsdu(SLAVE_1, 1000, OVERSIZED_OBJECT_COUNT);
    master.sendAsdu(oversized);

    // Nothing reached the bus (the over-length frame was never recorded) and the queue drained.
    assertTrue(userDataFrames().isEmpty(), "an unframeable command must not reach the bus");
    assertEquals(0, master.pendingSendCount(), "the command is removed from the queue");

    // The facade is handed a negative confirmation echoing the command, so it fails the waiting
    // operation promptly instead of by a generic timeout.
    assertEquals(1, events.asdus().size(), "the rejection is delivered through onAsdu");
    Asdu rejection = events.asdus().get(0);
    assertTrue(rejection.negative(), "the rejection sets the P/N bit");
    assertEquals(Cause.UNKNOWN_CAUSE, rejection.cause());
    assertEquals(oversized.type(), rejection.type(), "echoes the command type");
    assertEquals(oversized.commonAddress(), rejection.commonAddress(), "echoes the common address");
    assertEquals(oversized.objects(), rejection.objects(), "echoes the addressed object(s)");

    // One unframeable command must not close the session or stop data transfer.
    assertEquals(0, events.closedCount(), "an unframeable command must not close the session");
    assertTrue(master.isDataTransferStarted(), "data transfer stays started");

    // The slave-1 transaction path is not wedged: a subsequent normal command to slave 1 still goes
    // out as a send/confirm user-data frame, carrying the post-reset FCB the failed send rolled
    // back
    // (the FCB is only consumed on a confirmed transaction, so the rolled-back send never spent
    // it).
    Asdu normal = singlePointAsdu(SLAVE_1, 1, 1);
    master.sendAsdu(normal);

    List<Ft12Frame.Variable> userData = userDataFrames();
    assertEquals(1, userData.size(), "the next command to the same slave still goes out");
    Ft12Frame.Variable frame = userData.get(0);
    assertEquals(SLAVE_1, frame.linkAddress());
    assertEquals(FC_USER_DATA, frame.control().functionCode());
    assertTrue(
        frame.control().fcb(), "the rolled-back FCB is unspent, so this send still uses FCB=1");
    assertEquals(normal, frame.asdu(), "the normal command is framed verbatim");

    // No new rejection was synthesized for the normal command; the only onAsdu remains the first
    // rejection, confirming the second command is in flight (awaiting its acknowledgement), not
    // failed.
    assertEquals(1, events.asdus().size(), "a normal command is sent, not rejected");
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
    master.onFrame(secondaryFixed(FC_STATUS_OF_LINK, 2));
    master.onFrame(secondaryFixed(FC_ACK, 2));
    output.clear();
  }

  /** Returns the captured user-data (FC3) variable frames, in send order. */
  private List<Ft12Frame.Variable> userDataFrames() {
    List<Ft12Frame.Variable> userData = new ArrayList<>();
    for (Ft12Frame frame : output.frames()) {
      if (frame instanceof Ft12Frame.Variable variable
          && variable.control().functionCode() == FC_USER_DATA) {
        userData.add(variable);
      }
    }
    return userData;
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
   * Builds a non-sequence {@code M_SP_NA_1} ASDU carrying {@code count} single-point-information
   * objects at consecutive information-object addresses, stamped with the given common address.
   *
   * @param commonAddress the common address selecting the target slave.
   * @param baseIoa the first information-object address; subsequent objects increment from it.
   * @param count the number of information objects.
   * @return the ASDU.
   */
  private static Asdu singlePointAsdu(int commonAddress, int baseIoa, int count) {
    List<InformationObject> objects = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      objects.add(
          new SinglePointInformation(
              InformationObjectAddress.of(baseIoa + i),
              true,
              new Qds(false, false, false, false, false)));
    }
    return new Asdu(
        AsduType.M_SP_NA_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(commonAddress),
        objects);
  }

  /**
   * Records every {@link Ft12Frame} the engine emits, framing each through {@link Ft12Framer}
   * exactly as the real {@code Cs101Binding} does. A variable frame whose user data exceeds {@code
   * 255} octets makes {@code Ft12Framer.encode} throw an {@link IllegalArgumentException} before
   * the frame is recorded — and {@code encode} releases its own buffer on that throw — so this
   * faithfully reproduces the production framing failure without leaking a buffer.
   */
  private static final class FramingOutput implements Ft12LinkLayer.Output {

    private final List<Ft12Frame> frames = new ArrayList<>();

    @Override
    public void send(Ft12Frame frame) {
      // encode throws (and releases its own buffer) for an over-length variable frame; on success
      // it returns a buffer this fake releases at once, mirroring the transport write-and-free.
      ByteBuf buffer = Ft12Framer.encode(frame, PROFILE, LINK_ADDRESS_LENGTH, ALLOC);
      buffer.release();
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
