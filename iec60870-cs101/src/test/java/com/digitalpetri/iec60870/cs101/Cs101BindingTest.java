package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingClientTransport;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import com.digitalpetri.iec60870.test.common.RecordingServerConnection;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link Cs101Binding} over an in-memory fake octet transport, asserting the binding
 * responsibilities: an outbound {@link Asdu} is framed and handed to the transport, an inbound
 * whole-frame {@link ByteBuf} is deframed and delivered through {@link Session.Events#onAsdu}, and
 * the two loss paths — a transport drop and a failed send — route to {@link
 * Session.Events#onConnectionLost} (which the {@link RecordingEvents} fixture records as {@link
 * Session.Events#onClosed}). An undecodable inbound frame, by contrast, is a recoverable per-frame
 * error: it is dropped without closing the session or the connection, so FT1.2 retransmission can
 * recover it.
 *
 * <p>This is the FT1.2 peer of {@code Cs104BindingTest}, mirroring it case-for-case with {@link
 * Ft12Frame}/{@link Ft12Framer}/{@link LinkSettings} in place of the 104 {@code Apdu}/{@code
 * ApduFramer}/{@code ApciSettings}. The test stays at the {@code Ft12Frame}/{@code ByteBuf} level,
 * which is legal inside cs101.
 */
class Cs101BindingTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();
  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;
  private static final LinkSettings LINK = LinkSettings.balanced().build();
  private static final int LINK_ADDRESS_LENGTH = LINK.linkAddressLength();

  // FT1.2 function codes used to build the bring-up and data frames driven from the peer.
  private static final int FC_RESET_REMOTE_LINK = 0;
  private static final int FC_ACK = 0;
  private static final int FC_USER_DATA = 3;
  private static final int FC_STATUS_OF_LINK = 11;

  private static Asdu sampleAsdu() {
    InformationObject object =
        new SinglePointInformation(
            InformationObjectAddress.of(100), true, new Qds(false, false, false, false, false));
    return new Asdu(
        AsduType.M_SP_NA_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(object));
  }

  @Test
  void clientBindingFramesOutboundAsduToTransport() {
    RecordingClientTransport transport = new RecordingClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs101Binding binding = new Cs101Binding(LINK, PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();

    // The CLIENT drives the FT1.2 link-reset bring-up; startDataTransfer sends
    // request-status-of-link
    // and the peer's status-of-link (FC11) then positive acknowledgement (FC0) bring the link up.
    session.startDataTransfer();
    feedFrame(transport.listener(), secondary(FC_STATUS_OF_LINK));
    feedFrame(transport.listener(), secondary(FC_ACK));

    Asdu asdu = sampleAsdu();
    session.sendAsdu(asdu);

    // With the link available the session transmits the user-data frame immediately; the binding
    // frames it to a whole ByteBuf and hands it to the transport as the last captured frame.
    assertFalse(transport.sent().isEmpty());
    ByteBuf frame = transport.sent().get(transport.sent().size() - 1);
    Ft12Frame decoded = Ft12Framer.decode(PROFILE, LINK_ADDRESS_LENGTH, frame.duplicate());
    Ft12Frame.Variable userData = assertInstanceOf(Ft12Frame.Variable.class, decoded);
    assertEquals(asdu, userData.asdu());
  }

  @Test
  void clientBindingDeliversInboundFrameAsAsdu() {
    RecordingClientTransport transport = new RecordingClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs101Binding binding = new Cs101Binding(LINK, PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();

    // Feed an inbound user-data frame (primary FC3, fresh FCB) as a whole frame through the
    // transport
    // listener; the binding deframes it and the session's secondary process delivers the carried
    // ASDU
    // via Events.onAsdu.
    Asdu asdu = sampleAsdu();
    Ft12Frame inbound =
        new Ft12Frame.Variable(
            LinkControlField.primary(false, true, true, FC_USER_DATA), LINK.linkAddress(), asdu);
    feedFrame(transport.listener(), inbound);

    assertEquals(1, events.asdus().size());
    assertEquals(asdu, events.asdus().get(0));
  }

  @Test
  void clientBindingRoutesConnectionLossToOnClosed() {
    RecordingClientTransport transport = new RecordingClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs101Binding binding = new Cs101Binding(LINK, PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();

    RuntimeException cause = new RuntimeException("peer reset");
    assertNotNull(transport.listener());
    transport.listener().onConnectionLost(cause);

    assertEquals(1, events.closedCount());
    assertSame(cause, events.lastCloseCause());
  }

  @Test
  void clientBindingDropsUndecodableInboundFrameWithoutClosing() {
    RecordingClientTransport transport = new RecordingClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs101Binding binding = new Cs101Binding(LINK, PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();
    assertNotNull(transport.listener());

    // A length-valid inbound user-data frame whose checksum octet is flipped, modeling a single-bit
    // line error on a noisy serial link; Ft12Framer.decode throws an AsduDecodeException for the
    // checksum mismatch.
    Asdu asdu = sampleAsdu();
    Ft12Frame userData =
        new Ft12Frame.Variable(
            LinkControlField.primary(false, true, true, FC_USER_DATA), LINK.linkAddress(), asdu);
    ByteBuf corrupted = Ft12Framer.encode(userData, PROFILE, LINK_ADDRESS_LENGTH, ALLOC);
    // The checksum octet sits just before the 0x16 end octet; flipping it invalidates the frame
    // while leaving its length intact, so decode reaches and fails the checksum verification.
    int checksumIndex = corrupted.writerIndex() - 2;
    corrupted.setByte(checksumIndex, corrupted.getUnsignedByte(checksumIndex) ^ 0xFF);
    try {
      transport.listener().onFrame(corrupted);
    } finally {
      corrupted.release();
    }

    // A frame-level decode failure is recoverable on FT1.2: the garbled frame is dropped, the
    // session stays open (no onClosed/onConnectionLost), no ASDU is delivered, and the underlying
    // transport connection is left intact for the primary's retransmission to recover.
    assertEquals(0, events.closedCount(), "a decode failure must not close the session");
    assertEquals(
        0,
        transport.closeConnectionCount(),
        "a decode failure must not tear down the transport connection");
    assertEquals(0, events.asdus().size(), "no ASDU is delivered from a garbled frame");

    // A subsequent valid frame is still processed, proving the link was not torn down.
    feedFrame(transport.listener(), userData);
    assertEquals(1, events.asdus().size());
    assertEquals(asdu, events.asdus().get(0));
  }

  @Test
  void serverBindingFramesOutboundAndRoutesLoss() {
    RecordingServerConnection connection = new RecordingServerConnection();
    RecordingEvents events = new RecordingEvents();
    Cs101Binding binding = new Cs101Binding(LINK, PROFILE);

    Session session =
        binding.bindServer(
            connection, events, new ManualScheduler(), 0, OutboundQueuePolicy.DROP_OLDEST);
    session.onConnected();

    // Drive the peer's reset-of-remote-link so the SERVER session reaches the link-available state
    // and
    // begins transmitting queued user data.
    Ft12Frame reset =
        new Ft12Frame.FixedLength(
            LinkControlField.primary(true, false, false, FC_RESET_REMOTE_LINK), LINK.linkAddress());
    feedFrame(connection.listener(), reset);

    Asdu asdu = sampleAsdu();
    session.sendAsdu(asdu);
    assertFalse(
        connection.sent().isEmpty(),
        "server should frame the link-reset acknowledgement and/or the user-data frame");

    // A connection loss is routed through Events.onClosed.
    assertNotNull(connection.listener());
    connection.listener().onConnectionLost(null);
    assertEquals(1, events.closedCount());
    assertNull(events.lastCloseCause());
  }

  @Test
  void clientBindingSendFailureClosesSessionAndRoutesToOnConnectionLost() {
    FailingFrameTransport transport = new FailingFrameTransport();
    RecordingEvents events = new RecordingEvents();
    Cs101Binding binding = new Cs101Binding(LINK, PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();

    // The CLIENT drives the link-reset bring-up, which immediately transmits the
    // request-status-of-link frame. The send returns a synchronously failed future; the binding's
    // whenComplete error branch closes the session and routes the cause through
    // Session.Events.onConnectionLost (which defaults to onClosed).
    session.startDataTransfer();

    assertEquals(
        1, events.closedCount(), "a synchronous send failure closes the session exactly once");
    assertSame(
        transport.cause, events.lastCloseCause(), "the IOException is routed as the close cause");
    // The single frame the binding allocated was released by the failing transport, honoring the
    // octet transport's buffer-ownership contract (send() owns and releases the handed-over frame).
    assertEquals(1, transport.sendCount, "exactly one frame was handed to the failing transport");
  }

  // --- helpers -------------------------------------------------------------

  /** Builds a secondary (response) fixed-length frame with the configured link address. */
  private static Ft12Frame secondary(int functionCode) {
    return new Ft12Frame.FixedLength(
        LinkControlField.secondary(false, false, false, functionCode), LINK.linkAddress());
  }

  /**
   * Frames {@code frame} to a whole-frame buffer and delivers it through {@code listener},
   * releasing the buffer afterwards (the transport SPI's {@code onFrame} does not release the
   * buffer it is handed).
   */
  private static void feedFrame(@Nullable TransportListener listener, Ft12Frame frame) {
    assertNotNull(listener, "transport listener must be registered");
    ByteBuf encoded = Ft12Framer.encode(frame, PROFILE, LINK_ADDRESS_LENGTH, ALLOC);
    try {
      listener.onFrame(encoded);
    } finally {
      encoded.release();
    }
  }

  // --- fakes ---------------------------------------------------------------

  /**
   * A {@link ClientTransport} whose {@link #send(ByteBuf)} models a synchronous outbound write
   * failure: it releases the handed-over frame (as a real transport owns and releases it) and
   * returns an immediately-failed future so the binding's error branch fires.
   */
  private static final class FailingFrameTransport implements ClientTransport {

    private final IOException cause = new IOException("write failed");
    private int sendCount;

    @Override
    public CompletionStage<Void> connect() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> disconnect() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void closeConnection() {}

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      sendCount++;
      // Own and release the buffer exactly as a real transport would, then report the write failure
      // through a completed-exceptionally future (NOT by throwing).
      frame.release();
      return CompletableFuture.failedFuture(cause);
    }

    @Override
    public void setListener(TransportListener listener) {}
  }
}
