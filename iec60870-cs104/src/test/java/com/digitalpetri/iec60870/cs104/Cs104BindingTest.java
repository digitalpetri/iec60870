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
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

  @Test
  void clientBindingFramesOutboundAsduToTransport() {
    FakeClientTransport transport = new FakeClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();

    Asdu asdu = sampleAsdu();
    session.sendAsdu(asdu);

    // The CLIENT-role session transmits the I-frame immediately; the binding frames it to a whole
    // ByteBuf and hands it to the transport.
    assertEquals(1, transport.sent.size());
    ByteBuf frame = transport.sent.get(0);
    Apdu decoded = ApduFramer.decode(PROFILE, frame.duplicate());
    assertEquals(asdu, decoded.asdu());
  }

  @Test
  void clientBindingDeliversInboundFrameAsAsdu() {
    FakeClientTransport transport = new FakeClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();

    // Feed an inbound I-frame as a whole frame through the transport listener; the binding deframes
    // it and the session delivers the ASDU via Events.onAsdu.
    Asdu asdu = sampleAsdu();
    Apdu inbound = new Apdu(new ControlField.TypeI(0, 0), asdu);
    ByteBuf frame = ApduFramer.encode(inbound, PROFILE, ALLOC);
    try {
      assertNotNull(transport.listener);
      transport.listener.onFrame(frame);
    } finally {
      frame.release();
    }

    assertEquals(1, events.asdus.size());
    assertEquals(asdu, events.asdus.get(0));
  }

  @Test
  void clientBindingRoutesConnectionLossToOnClosed() {
    FakeClientTransport transport = new FakeClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);

    Session session = binding.bindClient(transport, events, new ManualScheduler());
    session.onConnected();

    RuntimeException cause = new RuntimeException("peer reset");
    assertNotNull(transport.listener);
    transport.listener.onConnectionLost(cause);

    assertEquals(1, events.closedCount);
    assertSame(cause, events.lastCloseCause);
  }

  @Test
  void clientBindingDecodeFailureClosesCurrentConnection() {
    FakeClientTransport transport = new FakeClientTransport();
    RecordingEvents events = new RecordingEvents();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);

    binding.bindClient(transport, events, new ManualScheduler());

    ByteBuf frame = ALLOC.buffer(1);
    frame.writeByte(0x00);
    try {
      assertNotNull(transport.listener);
      transport.listener.onFrame(frame);
    } finally {
      frame.release();
    }

    assertEquals(1, events.closedCount);
    assertInstanceOf(AsduDecodeException.class, events.lastCloseCause);
    assertEquals(
        1,
        transport.closeConnectionCount,
        "a client-side decode failure must close the current transport connection");
  }

  @Test
  void serverBindingFramesOutboundAndRoutesLoss() {
    FakeServerConnection connection = new FakeServerConnection();
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
      assertNotNull(connection.listener);
      connection.listener.onFrame(startFrame);
    } finally {
      startFrame.release();
    }

    Asdu asdu = sampleAsdu();
    session.sendAsdu(asdu);
    assertFalse(
        connection.sent.isEmpty(), "server should frame the STARTDT_CON and/or the I-frame");

    // A connection loss is routed through Events.onClosed.
    connection.listener.onConnectionLost(null);
    assertEquals(1, events.closedCount);
    assertNull(events.lastCloseCause);
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
        1, events.closedCount, "a synchronous send failure closes the session exactly once");
    assertSame(
        transport.cause, events.lastCloseCause, "the IOException is routed as the close cause");
    // The single frame the binding allocated was released by the failing transport; ParanoidLeak-
    // Detection (active via the surefire arg) would otherwise fail the build on a leak.
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

  private static final class FakeClientTransport implements ClientTransport {

    private final List<ByteBuf> sent = new ArrayList<>();
    private @Nullable TransportListener listener;
    private int closeConnectionCount;

    @Override
    public CompletionStage<Void> connect() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> disconnect() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void closeConnection() {
      closeConnectionCount++;
    }

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      // The binding transfers ownership to the transport; keep a copy for assertions and release
      // the handed-over buffer, mirroring a real transport's write-and-release.
      sent.add(frame.copy());
      frame.release();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setListener(TransportListener listener) {
      this.listener = listener;
    }
  }

  private static final class FakeServerConnection implements ServerTransportConnection {

    private final List<ByteBuf> sent = new ArrayList<>();
    private @Nullable TransportListener listener;

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      sent.add(frame.copy());
      frame.release();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setListener(TransportListener listener) {
      this.listener = listener;
    }

    @Override
    public void close() {}

    @Override
    public SocketAddress remoteAddress() {
      return new InetSocketAddress("127.0.0.1", 2404);
    }

    @Override
    public Optional<Certificate> peerCertificate() {
      return Optional.empty();
    }
  }

  private static final class RecordingEvents implements Session.Events {

    private final List<Asdu> asdus = new ArrayList<>();
    private int closedCount;
    private @Nullable Throwable lastCloseCause;

    @Override
    public void onAsdu(Asdu asdu) {
      asdus.add(asdu);
    }

    @Override
    public void onDataTransferStateChanged(boolean started) {}

    @Override
    public void onClosed(@Nullable Throwable cause) {
      closedCount++;
      lastCloseCause = cause;
    }
  }
}
