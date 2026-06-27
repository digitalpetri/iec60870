package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.digitalpetri.iec60870.AsduDecodeException;
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
 * Exercises {@link Cs104Binding} over an in-memory fake octet transport, asserting the three
 * binding responsibilities: an outbound {@link Asdu} is framed and handed to the transport, an
 * inbound whole-frame {@link ByteBuf} is deframed and delivered through {@link
 * Session.Events#onAsdu}, and {@link Session.Events#onClosed} fires when the transport reports a
 * connection loss.
 *
 * <p>The test stays at the {@code Apdu}/{@code ByteBuf} level, which is legal inside cs104.
 */
class Cs104BindingTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();
  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;

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

  /**
   * Encodes {@code apdu} to a whole-frame {@link ByteBuf} and feeds it through the listener, then
   * releases it — the binding's {@code onFrame} does not own or release the buffer.
   */
  private static void feedFrame(@Nullable TransportListener listener, Apdu apdu) {
    assertNotNull(listener);
    ByteBuf frame = ApduFramer.encode(apdu, PROFILE, ALLOC);
    try {
      listener.onFrame(frame);
    } finally {
      frame.release();
    }
  }

  @Test
  void clientBindingFramesOutboundAsduToTransport() {
    RecordingClientTransport transport = new RecordingClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();

    Asdu asdu = sampleAsdu();
    session.sendAsdu(asdu);

    // The CLIENT-role session transmits the I-frame immediately; the binding frames it to a whole
    // ByteBuf and hands it to the transport.
    assertEquals(1, transport.sent().size());
    ByteBuf frame = transport.sent().get(0);
    Apdu decoded = ApduFramer.decode(PROFILE, frame.duplicate());
    assertEquals(asdu, decoded.asdu());
  }

  @Test
  void clientBindingDeliversInboundFrameAsAsdu() {
    RecordingClientTransport transport = new RecordingClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();

    // Drive the STARTDT handshake so data transfer is started before any inbound I-frame: an
    // I-frame received while data transfer is stopped self-closes the session without delivery.
    session.startDataTransfer();
    feedFrame(transport.listener(), new Apdu(new ControlField.TypeU(UFunction.STARTDT_CON), null));

    // Feed an inbound I-frame as a whole frame through the transport listener; the binding deframes
    // it and the session delivers the ASDU via Events.onAsdu.
    Asdu asdu = sampleAsdu();
    feedFrame(transport.listener(), new Apdu(new ControlField.TypeI(0, 0), asdu));

    assertEquals(1, events.asdus().size());
    assertEquals(asdu, events.asdus().get(0));
  }

  @Test
  void clientBindingRoutesConnectionLossToOnClosed() {
    RecordingClientTransport transport = new RecordingClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();

    RuntimeException cause = new RuntimeException("peer reset");
    assertNotNull(transport.listener());
    transport.listener().onConnectionLost(cause);

    assertEquals(1, events.closedCount());
    assertSame(cause, events.lastCloseCause());
  }

  @Test
  void clientBindingDecodeFailureClosesCurrentConnection() {
    RecordingClientTransport transport = new RecordingClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);

    binding.bindClient(transport, events, new ManualScheduler());

    ByteBuf frame = ALLOC.buffer(1);
    frame.writeByte(0x00);
    try {
      assertNotNull(transport.listener());
      transport.listener().onFrame(frame);
    } finally {
      frame.release();
    }

    assertEquals(1, events.closedCount());
    assertInstanceOf(AsduDecodeException.class, events.lastCloseCause());
    assertEquals(
        1,
        transport.closeConnectionCount(),
        "a client-side decode failure must close the current transport connection");
  }

  @Test
  void serverBindingFramesOutboundAndRoutesLoss() {
    RecordingServerConnection connection = new RecordingServerConnection();
    RecordingEvents events = new RecordingEvents();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);

    Session session =
        binding.bindServer(
            connection, events, new ManualScheduler(), 0, OutboundQueuePolicy.DROP_OLDEST);
    session.onConnected();

    // Drive STARTDT so the SERVER session begins transmitting queued monitor data.
    Apdu startdt = new Apdu(new ControlField.TypeU(UFunction.STARTDT_ACT), null);
    ByteBuf startFrame = ApduFramer.encode(startdt, PROFILE, ALLOC);
    try {
      assertNotNull(connection.listener());
      connection.listener().onFrame(startFrame);
    } finally {
      startFrame.release();
    }

    Asdu asdu = sampleAsdu();
    session.sendAsdu(asdu);
    assertFalse(
        connection.sent().isEmpty(), "server should frame the STARTDT_CON and/or the I-frame");

    // A connection loss is routed through Events.onClosed.
    connection.listener().onConnectionLost(null);
    assertEquals(1, events.closedCount());
    assertNull(events.lastCloseCause());
  }

  @Test
  void clientBindingSendFailureClosesSessionAndRoutesToOnConnectionLost() {
    FailingFrameTransport transport = new FailingFrameTransport();
    RecordingEvents events = new RecordingEvents();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    // CLIENT role starts data transfer on connect, so an outbound I-frame is transmitted
    // immediately.
    session.onConnected();

    // The send returns a synchronously failed future; the binding's whenComplete error branch
    // closes the session and routes the cause through Session.Events.onConnectionLost (which
    // defaults to onClosed).
    session.sendAsdu(sampleAsdu());

    assertEquals(
        1, events.closedCount(), "a synchronous send failure closes the session exactly once");
    assertSame(
        transport.cause, events.lastCloseCause(), "the IOException is routed as the close cause");
    // The single frame the binding allocated was released by the failing transport, honoring the
    // octet transport's buffer-ownership contract (send() owns and releases the handed-over frame).
    assertEquals(1, transport.sendCount, "exactly one frame was handed to the failing transport");
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
