package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.UndecodableAsduPolicy;
import com.digitalpetri.iec60870.UnsupportedAsduTypeException;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingClientTransport;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import com.digitalpetri.iec60870.test.common.RecordingServerConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link ApciDiagnostics} events a {@link Cs104Binding} reports: each framed and deframed
 * APDU with its control field and length, decode failures with the offending frame, and the raw
 * frame trace.
 */
class Cs104BindingDiagnosticsTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();
  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;

  private static final String VALID_SINGLE_POINT = "01010300010064000001";
  private static final String PRIVATE_TYPE_ID = "88010300010064000001";

  private final ManualScheduler scheduler = new ManualScheduler();
  private final RecordingClientTransport transport = new RecordingClientTransport();
  private final RecordingEvents events = new RecordingEvents();
  private final RecordingDiagnostics diagnostics = new RecordingDiagnostics();

  @Test
  void everyFrameIsReportedWithItsControlFieldAndLength() {
    Session session = startedClient(UndecodableAsduPolicy.DEFAULT, diagnostics);
    feed(new ControlField.TypeI(0, 0), hex(VALID_SINGLE_POINT));
    session.sendAsdu(events.asdus().get(0));
    feed(new ControlField.TypeS(1));

    assertEquals(
        List.of(
            "sent U(STARTDT_ACT) 6",
            "received U(STARTDT_CON) 6",
            "received I(N(S)=0 N(R)=0) 16",
            "sent I(N(S)=0 N(R)=1) 16",
            "received S(N(R)=1) 6"),
        diagnostics.log);
    assertEquals(6, transport.sent().get(0).readableBytes());
    assertEquals(16, transport.sent().get(1).readableBytes());
  }

  @Test
  void framingErrorReportsTheWholeFrame() {
    startedClient(UndecodableAsduPolicy.DEFAULT, diagnostics);
    // A U-format control octet with no defined function.
    byte[] frame = hex("68040f000000");

    feedRaw(frame);

    assertEquals(List.of("decodeError AsduDecodeException"), diagnostics.lines("decodeError"));
    assertArrayEquals(frame, diagnostics.decodeErrorFrames.get(0));
    assertEquals(1, transport.closeConnectionCount());
  }

  @Test
  void undecodableAsduIsReportedWithItsFrameAndStillReceived() {
    startedClient(UndecodableAsduPolicy.REPORT_AND_CONTINUE, diagnostics);
    ByteBuf frame = ApduFramer.encode(new ControlField.TypeI(0, 0), hex(PRIVATE_TYPE_ID), ALLOC);
    byte[] octets = ByteBufUtil.getBytes(frame);

    feedFrame(frame);

    assertEquals(
        List.of("received I(N(S)=0 N(R)=0) 16", "decodeError UnsupportedAsduTypeException"),
        diagnostics.log.subList(2, 4));
    assertArrayEquals(octets, diagnostics.decodeErrorFrames.get(0));
    assertInstanceOf(UnsupportedAsduTypeException.class, diagnostics.decodeErrorCauses.get(0));
    assertEquals(1, events.undecodableAsdus().size());
  }

  @Test
  void strictPolicyCountsTheFrameAndReportsTheDecodeErrorBeforeClosing() {
    startedClient(UndecodableAsduPolicy.STRICT, diagnostics);

    feed(new ControlField.TypeI(0, 0), hex("0d0103000100640000000000"));

    assertEquals(
        List.of("received I(N(S)=0 N(R)=0) 18", "decodeError AsduDecodeException"),
        diagnostics.log.subList(2, 4));
    assertInstanceOf(AsduDecodeException.class, diagnostics.decodeErrorCauses.get(0));
    assertEquals(1, transport.closeConnectionCount());
    assertEquals(0, events.undecodableAsdus().size());
  }

  @Test
  void frameTraceRunsOnlyWhileEnabledAndLendsReadOnlyViews() {
    Session session = startedClient(UndecodableAsduPolicy.DEFAULT, diagnostics);
    assertEquals(List.of(), diagnostics.tracedFrames, "trace is off");

    diagnostics.trace = true;
    ByteBuf inbound =
        ApduFramer.encode(new ControlField.TypeI(0, 0), hex(VALID_SINGLE_POINT), ALLOC);
    byte[] inboundOctets = ByteBufUtil.getBytes(inbound);
    feedFrame(inbound);
    session.sendAsdu(events.asdus().get(0));

    // The listener read each view to its end; the frames still decoded and went out whole.
    assertEquals(1, events.asdus().size());
    assertEquals(List.of("frame IN 16", "frame OUT 16"), diagnostics.lines("frame"));
    assertArrayEquals(inboundOctets, diagnostics.tracedFrames.get(0));
    ByteBuf written = transport.sent().get(transport.sent().size() - 1);
    assertEquals(16, written.readableBytes());
    assertArrayEquals(ByteBufUtil.getBytes(written), diagnostics.tracedFrames.get(1));
    assertEquals(List.of(true, true), diagnostics.tracedReadOnly);

    diagnostics.trace = false;
    feed(new ControlField.TypeS(1));
    assertEquals(2, diagnostics.tracedFrames.size());
  }

  @Test
  void throwingListenerDoesNotDisturbTheSession() {
    Session session = startedClient(UndecodableAsduPolicy.DEFAULT, new ThrowingDiagnostics());
    assertTrue(session.isDataTransferStarted());

    feed(new ControlField.TypeI(0, 0), hex(VALID_SINGLE_POINT));
    feed(new ControlField.TypeI(1, 0), hex(PRIVATE_TYPE_ID));
    session.sendAsdu(events.asdus().get(0));

    assertEquals(1, events.asdus().size());
    assertEquals(1, events.undecodableAsdus().size());
    assertEquals(0, events.closedCount());
    assertEquals(2, transport.sent().size());
  }

  @Test
  void serverBindingReportsToThePerConnectionListener() {
    Cs104Binding binding =
        new Cs104Binding(
            ApciSettings.defaults(),
            PROFILE,
            null,
            UndecodableAsduPolicy.DEFAULT,
            ApciDiagnostics.NONE,
            Map.of("site", "north"));
    var connection = new RecordingServerConnection();
    Session session =
        binding.bindServer(
            connection, events, scheduler, 0, OutboundQueuePolicy.DROP_OLDEST, diagnostics);
    session.onConnected();
    TransportListener listener = connection.listener();
    assertNotNull(listener);
    ByteBuf frame =
        ApduFramer.encode(
            new Apdu(new ControlField.TypeU(UFunction.STARTDT_ACT), null), PROFILE, ALLOC);
    try {
      listener.onFrame(frame);
    } finally {
      frame.release();
    }

    assertEquals(List.of("received U(STARTDT_ACT) 6", "sent U(STARTDT_CON) 6"), diagnostics.log);
  }

  // --- Helpers -----------------------------------------------------------------------------------

  private Session startedClient(UndecodableAsduPolicy policy, ApciDiagnostics listener) {
    Cs104Binding binding =
        new Cs104Binding(ApciSettings.defaults(), PROFILE, null, policy, listener, Map.of());
    Session session = binding.bindClient(transport, events, scheduler);
    session.onConnected();
    session.startDataTransfer();
    feed(new ControlField.TypeU(UFunction.STARTDT_CON));
    return session;
  }

  private void feed(ControlField.TypeI control, byte[] asdu) {
    feedFrame(ApduFramer.encode(control, asdu, ALLOC));
  }

  private void feed(ControlField control) {
    feedFrame(ApduFramer.encode(new Apdu(control, null), PROFILE, ALLOC));
  }

  private void feedRaw(byte[] octets) {
    feedFrame(ALLOC.buffer().writeBytes(octets));
  }

  private void feedFrame(ByteBuf frame) {
    TransportListener listener = transport.listener();
    assertNotNull(listener);
    try {
      listener.onFrame(frame);
    } finally {
      frame.release();
    }
  }

  private static byte[] hex(String hex) {
    return HexFormat.of().parseHex(hex);
  }
}
