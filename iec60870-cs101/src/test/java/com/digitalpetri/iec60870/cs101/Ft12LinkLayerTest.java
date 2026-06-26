package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolTimeoutException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Ft12LinkLayer}, the balanced FT1.2 link state machine: the CLIENT
 * link-reset bring-up, the stop-and-wait FCB toggle for user data, confirm-timer retransmission and
 * timeout close, the secondary receive path with FCB-keyed retransmission detection, the
 * single-character acknowledgement toggle, and the role-specific {@code startDataTransfer}/{@code
 * stopDataTransfer} contract.
 *
 * <p>The link layer is driven through a {@link ManualScheduler} virtual clock and a recording
 * {@link RecordingOutput}/{@link RecordingEvents} so every emitted {@link Ft12Frame} and lifecycle
 * event is asserted deterministically with no real time elapsing. Expected behavior is derived from
 * the FT1.2 balanced design (request-status-of-link &rarr; reset-of-remote-link &rarr; available;
 * window of one; FCB starts at {@code 1} on the primary side and {@code 0} on the secondary side
 * after reset), not from the production engine's internals.
 */
class Ft12LinkLayerTest {

  /**
   * The default balanced parameters: confirm 200ms, repeat 1000ms, 3 retries, single-char ack on.
   */
  private static final LinkSettings SETTINGS = LinkSettings.balanced().build();

  private static final long CONFIRM_MILLIS = 200;
  private static final long REPEAT_MILLIS = 1000;
  private static final long LINK_STATE_MILLIS = 5000;

  /** The default balanced link address used by {@link #SETTINGS}. */
  private static final int LINK_ADDRESS = 1;

  // FT1.2 function codes used in the assertions (PRM context disambiguates FC0).
  private static final int FC_RESET_REMOTE_LINK = 0; // primary
  private static final int FC_USER_DATA = 3; // primary, FCV=1
  private static final int FC_REQUEST_STATUS_OF_LINK = 9; // primary
  private static final int FC_ACK = 0; // secondary, positive confirm
  private static final int FC_STATUS_OF_LINK = 11; // secondary

  private ManualScheduler scheduler;
  private RecordingOutput output;
  private RecordingEvents events;

  @BeforeEach
  void setUp() {
    scheduler = new ManualScheduler();
    output = new RecordingOutput();
    events = new RecordingEvents();
  }

  private Ft12LinkLayer newClient() {
    return newClient(SETTINGS);
  }

  private Ft12LinkLayer newClient(LinkSettings settings) {
    return new Ft12LinkLayer(Ft12LinkLayer.Role.CLIENT, settings, scheduler, output, events);
  }

  private Ft12LinkLayer newServer(LinkSettings settings) {
    return new Ft12LinkLayer(Ft12LinkLayer.Role.SERVER, settings, scheduler, output, events);
  }

  // --- CLIENT bring-up -------------------------------------------------------------------------

  @Test
  void clientBringUpReachesLinkAvailable() throws Exception {
    Ft12LinkLayer client = newClient();
    client.onConnected();

    // startDataTransfer drives the link-reset bring-up: first a request-status-of-link (FC9).
    CompletionStage<Void> start = client.startDataTransfer();
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength fc9 =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertTrue(fc9.control().prm(), "request-status-of-link is a primary frame");
    assertEquals(FC_REQUEST_STATUS_OF_LINK, fc9.control().functionCode());
    assertFalse(client.isDataTransferStarted());
    assertFalse(start.toCompletableFuture().isDone(), "the start stage waits for the link reset");

    // The peer's status-of-link (FC11) prompts a reset-of-remote-link (FC0).
    client.onFrame(inboundSecondary(FC_STATUS_OF_LINK));
    assertEquals(2, output.frames().size());
    Ft12Frame.FixedLength fc0 =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(1));
    assertTrue(fc0.control().prm(), "reset-of-remote-link is a primary frame");
    assertEquals(FC_RESET_REMOTE_LINK, fc0.control().functionCode());
    assertFalse(
        client.isDataTransferStarted(), "the link is not available until the reset is acked");

    // The peer's positive acknowledgement (FC0) completes the bring-up.
    client.onFrame(inboundSecondary(FC_ACK));
    assertTrue(client.isDataTransferStarted());
    assertEquals(
        List.of(Boolean.TRUE),
        events.dataTransferChanges(),
        "onDataTransferStateChanged(true) must fire exactly once");
    start.toCompletableFuture().get(1, TimeUnit.SECONDS);
  }

  // --- Data send: window of one + FCB stop-and-wait --------------------------------------------

  @Test
  void userDataIsStopAndWaitWithTogglingFcb() {
    Ft12LinkLayer client = newClient();
    client.onConnected();
    bringUpClient(client);

    Asdu a = asdu(10);
    Asdu b = asdu(11);

    // The first user-data frame after a reset carries FCB=1.
    client.sendAsdu(a);
    assertEquals(1, output.frames().size());
    Ft12Frame.Variable first = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertTrue(first.control().prm());
    assertTrue(first.control().fcv());
    assertEquals(FC_USER_DATA, first.control().functionCode());
    assertTrue(first.control().fcb(), "the first user-data frame after reset carries FCB=1");
    assertEquals(a, first.asdu());

    // Window of one: a second send while the first is unconfirmed emits nothing more and queues.
    client.sendAsdu(b);
    assertEquals(1, output.frames().size(), "window of one: only one outstanding primary frame");
    assertEquals(1, client.pendingSendCount());

    // The positive acknowledgement flushes the queued ASDU with the toggled FCB=0.
    output.clear();
    client.onFrame(inboundSecondary(FC_ACK));
    assertEquals(1, output.frames().size());
    Ft12Frame.Variable second = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertEquals(FC_USER_DATA, second.control().functionCode());
    assertFalse(second.control().fcb(), "the next user-data frame toggles to FCB=0");
    assertEquals(b, second.asdu());
    assertEquals(0, client.pendingSendCount());

    // The second acknowledgement clears the outstanding frame; nothing remains queued.
    client.onFrame(inboundSecondary(FC_ACK));
    assertEquals(0, client.pendingSendCount());
    assertNull(events.lastCloseCause());
  }

  // --- Retransmission and timeout close --------------------------------------------------------

  @Test
  void unconfirmedUserDataIsRetransmittedWithSameFcb() {
    Ft12LinkLayer client = newClient();
    client.onConnected();
    bringUpClient(client);

    Asdu a = asdu(10);
    client.sendAsdu(a);
    Ft12Frame.Variable sent = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    boolean fcb = sent.control().fcb();
    output.clear();

    // The confirm timer expires with the frame unacknowledged: retransmit verbatim.
    scheduler.advance(CONFIRM_MILLIS, TimeUnit.MILLISECONDS);

    assertEquals(1, output.frames().size());
    Ft12Frame.Variable retransmit =
        assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertEquals(FC_USER_DATA, retransmit.control().functionCode());
    assertEquals(fcb, retransmit.control().fcb(), "a retransmission keeps the same FCB");
    assertEquals(a, retransmit.asdu());
    assertEquals(sent, retransmit, "the retransmitted frame is identical to the original");
    assertNull(events.lastCloseCause());
  }

  @Test
  void closesWithTimeoutAfterMaxRetriesExhausted() {
    LinkSettings settings = LinkSettings.balanced().maxRetries(1).build();
    Ft12LinkLayer client = newClient(settings);
    client.onConnected();
    bringUpClient(client);

    client.sendAsdu(asdu(10));

    // The first confirm timeout retransmits once; the link stays open.
    scheduler.advance(CONFIRM_MILLIS, TimeUnit.MILLISECONDS);
    assertNull(events.lastCloseCause());

    // The repeat timeout exhausts the single retry and closes with a timeout cause.
    scheduler.advance(REPEAT_MILLIS, TimeUnit.MILLISECONDS);
    assertInstanceOf(ProtocolTimeoutException.class, events.lastCloseCause());
    assertEquals(1, events.closedCount());
  }

  // --- Secondary (receive) path ----------------------------------------------------------------

  @Test
  void inboundUserDataDeliversOnceAndReplaysAckOnRetransmission() {
    Ft12LinkLayer client = newClient();
    client.onConnected();
    bringUpClient(client);

    Asdu first = asdu(10);

    // A fresh primary FC3 (FCB=1) from the peer: deliver once and acknowledge with the default
    // single-character ack (0xE5).
    client.onFrame(inboundUserData(true, first));
    assertEquals(1, events.asdus().size());
    assertEquals(first, events.asdus().get(0));
    assertEquals(1, output.frames().size());
    assertInstanceOf(
        Ft12Frame.SingleChar.class,
        output.frames().get(0),
        "the default acknowledgement is the 0xE5 single-character frame");

    // The same FC3 (unchanged FCB) is a retransmission: replay the ack but do not re-deliver.
    client.onFrame(inboundUserData(true, first));
    assertEquals(1, events.asdus().size(), "a retransmission must not be delivered a second time");
    assertEquals(2, output.frames().size());
    assertInstanceOf(Ft12Frame.SingleChar.class, output.frames().get(1));

    // A toggled FCB marks a genuinely new frame: deliver again.
    Asdu second = asdu(11);
    client.onFrame(inboundUserData(false, second));
    assertEquals(2, events.asdus().size());
    assertEquals(second, events.asdus().get(1));
    assertEquals(3, output.frames().size());
  }

  @Test
  void secondaryAckIsFixedFrameWhenSingleCharDisabled() {
    LinkSettings settings = LinkSettings.balanced().useSingleCharAck(false).build();
    Ft12LinkLayer client = newClient(settings);
    client.onConnected();
    bringUpClient(client);

    client.onFrame(inboundUserData(true, asdu(10)));

    assertEquals(1, events.asdus().size());
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength ack =
        assertInstanceOf(
            Ft12Frame.FixedLength.class,
            output.frames().get(0),
            "with the single-character ack disabled the ack is a fixed FC0 secondary frame");
    assertFalse(ack.control().prm(), "an acknowledgement is a secondary frame");
    assertEquals(FC_ACK, ack.control().functionCode());
  }

  // --- Idle keep-alive -------------------------------------------------------------------------

  @Test
  void userDataQueuedDuringKeepAliveIsSentWhenTheProbeIsConfirmed() {
    Ft12LinkLayer client = newClient();
    client.onConnected();
    bringUpClient(client);

    // An idle link emits a request-status-of-link (FC9) keep-alive probe, occupying the window.
    scheduler.advance(LINK_STATE_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength probe =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertTrue(probe.control().prm());
    assertEquals(FC_REQUEST_STATUS_OF_LINK, probe.control().functionCode());

    // An ASDU submitted while the probe is outstanding must queue behind it (window of one).
    Asdu a = asdu(10);
    client.sendAsdu(a);
    assertEquals(
        1, output.frames().size(), "the queued ASDU must wait behind the keep-alive probe");
    assertEquals(1, client.pendingSendCount());

    // The probe's status-of-link (FC11) frees the window: the queued ASDU must now transmit.
    output.clear();
    client.onFrame(inboundSecondary(FC_STATUS_OF_LINK));
    assertEquals(1, output.frames().size(), "confirming the keep-alive must drain the send queue");
    Ft12Frame.Variable data = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertEquals(FC_USER_DATA, data.control().functionCode());
    assertEquals(a, data.asdu());
    assertEquals(0, client.pendingSendCount());
  }

  // --- SERVER role -----------------------------------------------------------------------------

  @Test
  void serverRejectsStartAndStopDataTransfer() {
    Ft12LinkLayer server = newServer(SETTINGS);
    server.onConnected();

    assertThrows(IllegalStateException.class, server::startDataTransfer);
    assertThrows(IllegalStateException.class, server::stopDataTransfer);
  }

  @Test
  void serverReachesLinkAvailableOnInboundReset() {
    // Disable the single-character ack so the positive ack to the reset is an explicit FC0 frame.
    LinkSettings settings = LinkSettings.balanced().useSingleCharAck(false).build();
    Ft12LinkLayer server = newServer(settings);
    server.onConnected();

    // The peer's reset-of-remote-link (FC0) brings the controlled station's link up.
    server.onFrame(inboundPrimaryReset());

    assertTrue(server.isDataTransferStarted());
    assertEquals(List.of(Boolean.TRUE), events.dataTransferChanges());
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength ack =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertFalse(ack.control().prm(), "the reset acknowledgement is a secondary frame");
    assertEquals(FC_ACK, ack.control().functionCode());
  }

  // --- close() ---------------------------------------------------------------------------------

  @Test
  void closeFiresNoOnClosed() throws Exception {
    Ft12LinkLayer client = newClient();
    client.onConnected();
    bringUpClient(client);

    client.sendAsdu(asdu(10)); // one frame in flight (window of one)
    client.sendAsdu(asdu(11)); // queued behind the in-flight frame
    assertEquals(1, client.pendingSendCount());
    assertTrue(client.awaitSendCapacity(0), "an unbounded queue always reports capacity");

    client.close();
    client.close(); // idempotent

    assertEquals(0, events.closedCount(), "close() must not invoke onClosed");

    // The timers were cancelled by close(): advancing the clock fires nothing further.
    scheduler.advance(REPEAT_MILLIS * 10, TimeUnit.MILLISECONDS);
    assertEquals(0, events.closedCount());
    assertNull(events.lastCloseCause());
  }

  // --- Fixtures --------------------------------------------------------------------------------

  /** Drives the CLIENT through the full link-reset bring-up and clears the captured frames. */
  private void bringUpClient(Ft12LinkLayer client) {
    client.startDataTransfer();
    client.onFrame(inboundSecondary(FC_STATUS_OF_LINK));
    client.onFrame(inboundSecondary(FC_ACK));
    output.clear();
  }

  /**
   * Builds a secondary (response) fixed frame from the peer; DIR is not significant to dispatch and
   * is left {@code false}.
   */
  private static Ft12Frame inboundSecondary(int functionCode) {
    return new Ft12Frame.FixedLength(
        LinkControlField.secondary(false, false, false, functionCode), LINK_ADDRESS);
  }

  /** Builds a primary reset-of-remote-link (FC0) request from the peer. */
  private static Ft12Frame inboundPrimaryReset() {
    return new Ft12Frame.FixedLength(
        LinkControlField.primary(false, false, false, FC_RESET_REMOTE_LINK), LINK_ADDRESS);
  }

  /** Builds a primary user-data (FC3, FCV=1) frame from the peer with the given FCB. */
  private static Ft12Frame inboundUserData(boolean fcb, Asdu asdu) {
    return new Ft12Frame.Variable(
        LinkControlField.primary(false, fcb, true, FC_USER_DATA), LINK_ADDRESS, asdu);
  }

  private static Asdu asdu(int ioa) {
    InformationObject object = new ReadCommand(InformationObjectAddress.of(ioa));
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(object));
  }

  /** Records every {@link Ft12Frame} the link layer emits, in send order. */
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
