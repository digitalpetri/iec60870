package com.digitalpetri.iec60870.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.client.ClientConfig;
import com.digitalpetri.iec60870.client.DefaultIec60870Client;
import com.digitalpetri.iec60870.cs101.Cs101Binding;
import com.digitalpetri.iec60870.cs101.Ft12Frame;
import com.digitalpetri.iec60870.cs101.Ft12Framer;
import com.digitalpetri.iec60870.cs101.LinkControlField;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.cs104.Apdu;
import com.digitalpetri.iec60870.cs104.ApduFramer;
import com.digitalpetri.iec60870.cs104.ControlField;
import com.digitalpetri.iec60870.cs104.Cs104Binding;
import com.digitalpetri.iec60870.cs104.UFunction;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SessionWriteCompletionIntegrationTest {
  private enum Mode {
    APCI,
    BALANCED,
    MASTER,
    SLAVE
  }

  private static final Asdu ASDU =
      new Asdu(
          AsduType.C_RD_NA_1,
          false,
          Cause.REQUEST,
          false,
          false,
          OriginatorAddress.none(),
          CommonAddress.of(1),
          List.of(new ReadCommand(InformationObjectAddress.of(10))));

  @Test
  void eachModeWaitsForTheTransportWrite() {
    for (Mode mode : Mode.values()) {
      try (var harness = new Harness(mode)) {
        var result = harness.session.sendAsduAsync(ASDU).toCompletableFuture();
        assertFalse(result.isDone(), mode + " must wait while queued");
        harness.startAndPoll();
        assertEquals(1, harness.transport.writes.size(), mode.toString());
        assertFalse(result.isDone(), mode + " must wait for transport completion");
        harness.transport.writes.get(0).complete(null);
        assertTrue(result.isDone(), mode.toString());
        assertFalse(result.isCompletedExceptionally(), mode.toString());
      }
    }
  }

  @Test
  void eachModeFailsOnTransportFailureAndClose() {
    for (Mode mode : Mode.values()) {
      try (var harness = new Harness(mode)) {
        var result = harness.session.sendAsduAsync(ASDU).toCompletableFuture();
        harness.startAndPoll();
        harness.transport.writes.get(0).completeExceptionally(new IOException("write failed"));
        assertTrue(result.isCompletedExceptionally(), mode.toString());
        assertTrue(
            harness.session.sendAsduAsync(ASDU).toCompletableFuture().isCompletedExceptionally());
      }
      try (var harness = new Harness(mode)) {
        var queued = harness.session.sendAsduAsync(ASDU).toCompletableFuture();
        harness.session.close();
        assertTrue(queued.isCompletedExceptionally(), mode + " queued close");
      }
    }
  }

  @Test
  void synchronousWriteFailureCanCloseDuringQueueDrain() {
    for (Mode mode : Mode.values()) {
      try (var harness = new Harness(mode)) {
        harness.transport.failImmediately = true;
        var result = harness.session.sendAsduAsync(ASDU).toCompletableFuture();
        harness.startAndPoll();
        assertTrue(result.isCompletedExceptionally(), mode.toString());
        assertEquals(0, harness.session.pendingSendCount());
      }
    }
  }

  @Test
  void closeFailsWritesAlreadyHandedToTheTransport() {
    for (Mode mode : Mode.values()) {
      try (var harness = new Harness(mode)) {
        var result = harness.session.sendAsduAsync(ASDU).toCompletableFuture();
        harness.startAndPoll();
        harness.session.close();
        assertTrue(result.isCompletedExceptionally(), mode.toString());
        harness.transport.writes.get(0).complete(null);
        assertTrue(result.isCompletedExceptionally(), mode.toString());
      }
    }
  }

  @Test
  void eachModeFailsUnfinishedWritesOnResetAndLateSuccessCannotReviveThem() {
    for (Mode mode : Mode.values()) {
      try (var harness = new Harness(mode)) {
        var result = harness.session.sendAsduAsync(ASDU).toCompletableFuture();
        harness.startAndPoll();
        harness.session.onConnected();
        assertTrue(result.isCompletedExceptionally(), mode.toString());
        harness.transport.writes.get(0).complete(null);
        assertTrue(result.isCompletedExceptionally(), mode.toString());
        assertEquals(0, harness.session.pendingSendCount());
      }
    }
  }

  @Test
  void lossyPublishingCannotEvictAnAcceptedReliableReply() {
    for (Mode mode : List.of(Mode.APCI, Mode.BALANCED, Mode.SLAVE)) {
      try (var harness = new Harness(mode)) {
        var reliable = harness.session.sendAsduAsync(ASDU).toCompletableFuture();
        harness.session.sendAsdu(ASDU);
        assertEquals(1, harness.session.pendingSendCount());
        assertFalse(reliable.isDone());
        assertTrue(
            harness.session.sendAsduAsync(ASDU).toCompletableFuture().isCompletedExceptionally());
        harness.startAndPoll();
        harness.transport.writes.get(0).complete(null);
        assertFalse(reliable.isCompletedExceptionally());
        assertTrue(reliable.isDone());
      }
    }
  }

  @Test
  void rawFacadeRelaysTransportFailure() {
    var transport = new WireTransport(Mode.APCI);
    var binding = new Cs104Binding(ApciSettings.defaults(), ProtocolProfile.iec104Default());
    var config =
        ClientConfig.builder()
            .startDataTransferOnConnect(false)
            .callbackExecutor(Runnable::run)
            .build();
    try (var client =
        new DefaultIec60870Client(
            transport,
            config,
            (events, clock) -> binding.bindClient(transport, events, clock),
            new ManualScheduler())) {
      client.connect();
      var result = client.sendAsync(ASDU).toCompletableFuture();
      assertFalse(result.isDone());
      transport.writes.get(0).completeExceptionally(new IOException("write failed"));
      assertTrue(result.isCompletedExceptionally());
    }
  }

  private static final class Harness implements AutoCloseable {
    private final Mode mode;
    private final WireTransport transport;
    private final Session session;

    Harness(Mode mode) {
      this.mode = mode;
      transport = new WireTransport(mode);
      var clock = new ManualScheduler();
      var events = new RecordingEvents();
      if (mode == Mode.APCI) {
        session =
            new Cs104Binding(ApciSettings.defaults(), ProtocolProfile.iec104Default())
                .bindServer(transport, events, clock, 1, OutboundQueuePolicy.DROP_OLDEST);
      } else {
        LinkSettings settings =
            mode == Mode.BALANCED
                ? LinkSettings.balanced().build()
                : LinkSettings.unbalanced().slaveAddresses(List.of(1)).build();
        var binding = new Cs101Binding(settings, ProtocolProfile.iec101Default());
        session =
            mode == Mode.MASTER
                ? binding.bindClient(transport, events, clock)
                : binding.bindServer(transport, events, clock, 1, OutboundQueuePolicy.DROP_OLDEST);
      }
      session.onConnected();
    }

    void startAndPoll() {
      if (mode == Mode.APCI) {
        feed(
            ApduFramer.encode(
                new Apdu(new ControlField.TypeU(UFunction.STARTDT_ACT), null),
                ProtocolProfile.iec104Default(),
                UnpooledByteBufAllocator.DEFAULT));
      } else if (mode == Mode.MASTER) {
        session.startDataTransfer();
        feed(new Ft12Frame.FixedLength(LinkControlField.secondary(false, false, false, 11), 1));
        feed(new Ft12Frame.SingleChar());
      } else {
        feed(new Ft12Frame.FixedLength(LinkControlField.primary(true, false, false, 0), 1));
        if (mode == Mode.SLAVE) {
          feed(new Ft12Frame.FixedLength(LinkControlField.primary(false, true, true, 11), 1));
        }
      }
    }

    void feed(Ft12Frame frame) {
      feed(
          Ft12Framer.encode(
              frame, ProtocolProfile.iec101Default(), 1, UnpooledByteBufAllocator.DEFAULT));
    }

    void feed(ByteBuf frame) {
      try {
        Objects.requireNonNull(transport.listener).onFrame(frame);
      } finally {
        frame.release();
      }
    }

    @Override
    public void close() {
      session.close();
    }
  }

  private static final class WireTransport implements ClientTransport, ServerTransportConnection {
    private final Mode mode;
    private final List<CompletableFuture<Void>> writes = new ArrayList<>();
    private @Nullable TransportListener listener;
    private boolean failImmediately;

    WireTransport(Mode mode) {
      this.mode = mode;
    }

    @Override
    public CompletionStage<Void> connect() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> disconnect() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    public void closeConnection() {}

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

    @Override
    public void setListener(TransportListener listener) {
      this.listener = listener;
    }

    @Override
    public CompletionStage<Void> send(ByteBuf buffer) {
      try {
        boolean userData =
            mode == Mode.APCI
                ? ApduFramer.decode(ProtocolProfile.iec104Default(), buffer).asdu() != null
                : Ft12Framer.decode(ProtocolProfile.iec101Default(), 1, buffer)
                    instanceof Ft12Frame.Variable;
        if (!userData) {
          return CompletableFuture.completedFuture(null);
        }
        if (failImmediately) return CompletableFuture.failedFuture(new IOException("write failed"));
        var write = new CompletableFuture<Void>();
        writes.add(write);
        return write;
      } finally {
        buffer.release();
      }
    }
  }
}
