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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression test for {@link BalancedEngine}'s handling of a user-data ASDU that cannot be framed
 * into a single FT1.2 variable frame.
 *
 * <p>An FT1.2 variable frame may carry at most {@code 255} user-data octets, so an oversized ASDU
 * makes the framer behind {@link Ft12LinkLayer.Output#send(Ft12Frame)} throw. The throw happens
 * <em>inside</em> {@link BalancedEngine}'s {@code flushSendQueue}, after the single-frame
 * stop-and-wait window has been opened ({@code pending} is set to {@code USER_DATA}) but before the
 * confirm timer is armed. If the throw escaped, the window would be wedged permanently: {@code
 * pending} would stay set with no timer ever clearing it, so no further user data could be sent on
 * the link. This test proves the engine instead rolls the transaction back, fails the offending
 * ASDU locally with a negative confirmation, and keeps the send path live.
 *
 * <p>The engine is driven through a {@link ManualScheduler} virtual clock and a {@link
 * FramingOutput} that runs every emitted {@link Ft12Frame} through {@link Ft12Framer} exactly as
 * the real {@code Cs101Binding} does, so an over-length user-data frame triggers the genuine {@link
 * IllegalArgumentException} rather than a stand-in.
 */
class BalancedEngineOversizedUserDataTest {

  /**
   * Balanced parameters with the single-character ack disabled, so every positive acknowledgement
   * is an explicit secondary FC0 fixed-length frame. Link address 1 (length 1) is the balanced
   * default.
   */
  private static final LinkSettings SETTINGS =
      LinkSettings.balanced().useSingleCharAck(false).build();

  /** The typical IEC 60870-5-101 field widths used by the framer behind the output. */
  private static final ProtocolProfile PROFILE = ProtocolProfile.iec101Default();

  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;
  private static final int LINK_ADDRESS_LENGTH = SETTINGS.linkAddressLength();
  private static final int LINK_ADDRESS = 1;

  // Primary (PRM=1) reset-of-remote-link function code (FCV=0), used to bring the link up.
  private static final int FC_RESET_REMOTE_LINK = 0;

  // Primary (PRM=1) send/confirm user-data function code (FCV=1).
  private static final int FC_USER_DATA = 3;

  /**
   * A non-sequence single-point-information ASDU of this many objects encodes to well over the
   * {@code 255}-octet FT1.2 user-data limit (header plus several octets per object), so it cannot
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

  /**
   * A {@link Ft12LinkLayer.Role#SERVER} engine reaches the available state by receiving the peer's
   * reset-of-remote-link, which keeps bring-up to a single inbound frame.
   */
  private BalancedEngine newEngine() {
    return new BalancedEngine(
        Ft12LinkLayer.Role.SERVER,
        SETTINGS,
        scheduler,
        output,
        events,
        0,
        OutboundQueuePolicy.DROP_OLDEST);
  }

  @Test
  void oversizedUserDataFailsTheAsduAndDoesNotWedgeTheSendPath() {
    BalancedEngine engine = newEngine();
    engine.onConnected();

    // Bring the link up: the peer's reset makes it available and the engine acknowledges.
    engine.onFrame(primaryReset());
    assertTrue(engine.isDataTransferStarted(), "the link is available after the peer reset");
    output.clear();

    // Publishing one ASDU too large to frame in a single FT1.2 variable frame makes output.send
    // throw from Ft12Framer (user data over 255 octets). The engine must roll back the transaction
    // it just opened rather than leaving the window wedged with a pending frame and no confirm
    // timer.
    Asdu oversized = singlePointAsdu(1000, OVERSIZED_OBJECT_COUNT);
    engine.sendAsdu(oversized);

    // Nothing reached the wire (the over-length frame was never recorded) and the queue drained.
    assertTrue(userDataFrames().isEmpty(), "an unframeable ASDU must not reach the wire");
    assertEquals(0, engine.pendingSendCount(), "the ASDU is removed from the send queue");

    // The façade is handed a negative confirmation echoing the ASDU, so it fails the waiting
    // operation promptly instead of by a generic confirm timeout.
    assertEquals(1, events.asdus().size(), "the rejection is delivered through onAsdu");
    Asdu rejection = events.asdus().get(0);
    assertTrue(rejection.negative(), "the rejection sets the P/N bit");
    assertEquals(Cause.UNKNOWN_CAUSE, rejection.cause());
    assertEquals(oversized.type(), rejection.type(), "echoes the ASDU type");
    assertEquals(oversized.commonAddress(), rejection.commonAddress(), "echoes the common address");
    assertEquals(oversized.objects(), rejection.objects(), "echoes the carried object(s)");

    // One unframeable ASDU must not close the session or take the link down.
    assertEquals(0, events.closedCount(), "an unframeable ASDU must not close the session");
    assertTrue(engine.isDataTransferStarted(), "the link stays available");

    // The send path is not wedged: a subsequent normal ASDU still goes out as a send/confirm
    // user-data frame, carrying the post-reset FCB the failed send rolled back (the FCB is spent
    // only on a confirmed transaction, so the rolled-back send never spent it).
    Asdu normal = singlePointAsdu(1, 1);
    engine.sendAsdu(normal);

    List<Ft12Frame.Variable> userData = userDataFrames();
    assertEquals(1, userData.size(), "the next ASDU still goes out");
    Ft12Frame.Variable frame = userData.get(0);
    assertEquals(LINK_ADDRESS, frame.linkAddress());
    assertEquals(FC_USER_DATA, frame.control().functionCode());
    assertTrue(
        frame.control().fcb(), "the rolled-back FCB is unspent, so this send still uses FCB=1");
    assertEquals(normal, frame.asdu(), "the normal ASDU is framed verbatim");

    // No new rejection was synthesized for the normal ASDU; the only onAsdu remains the first
    // rejection, confirming the second ASDU is in flight (awaiting its acknowledgement), not
    // failed.
    assertEquals(1, events.asdus().size(), "a normal ASDU is sent, not rejected");
  }

  // --- Fixtures --------------------------------------------------------------------------------

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

  /** An inbound reset-of-remote-link (FC0, FCV=0) primary frame from the peer. */
  private static Ft12Frame primaryReset() {
    return new Ft12Frame.FixedLength(
        LinkControlField.primary(true, false, false, FC_RESET_REMOTE_LINK), LINK_ADDRESS);
  }

  /**
   * Builds a non-sequence {@code M_SP_NA_1} ASDU carrying {@code count} single-point-information
   * objects at consecutive information-object addresses.
   *
   * @param baseIoa the first information-object address; subsequent objects increment from it.
   * @param count the number of information objects.
   * @return the ASDU.
   */
  private static Asdu singlePointAsdu(int baseIoa, int count) {
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
        CommonAddress.of(1),
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
