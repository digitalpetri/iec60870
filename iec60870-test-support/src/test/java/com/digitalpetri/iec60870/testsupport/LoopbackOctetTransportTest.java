package com.digitalpetri.iec60870.testsupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the in-JVM {@link LoopbackOctetTransport} relays whole-frame {@link ByteBuf}s
 * byte-identically in both directions, decoding each frame synchronously inside {@code onFrame}.
 *
 * <p>PARANOID leak detection is on for this module (see {@link ParanoidLeakDetection}); a leaked
 * relay buffer would surface as a {@code LEAK:} log.
 */
class LoopbackOctetTransportTest {

  /** A listener that copies each inbound frame's bytes out synchronously (never retaining it). */
  private static final class RecordingListener implements TransportListener {
    private final List<byte[]> frames = new ArrayList<>();

    @Override
    public void onFrame(ByteBuf frame) {
      byte[] bytes = new byte[frame.readableBytes()];
      frame.getBytes(frame.readerIndex(), bytes);
      frames.add(bytes);
    }

    @Override
    public void onConnectionLost(@Nullable Throwable cause) {}
  }

  @Test
  void roundTripsFramesInBothDirections() {
    LoopbackOctetTransport loopback = new LoopbackOctetTransport();
    ClientTransport client = loopback.client();
    ServerTransport server = loopback.server();

    RecordingListener clientListener = new RecordingListener();
    client.setListener(clientListener);

    RecordingListener serverListener = new RecordingListener();
    AtomicReference<@Nullable ServerTransportConnection> serverConnection = new AtomicReference<>();
    server.setConnectionHandler(
        connection -> {
          serverConnection.set(connection);
          connection.setListener(serverListener);
        });

    server.bind().toCompletableFuture().join();
    client.connect().toCompletableFuture().join();

    ServerTransportConnection connection = serverConnection.get();
    assertNotNull(connection, "server should have accepted the loopback connection");

    // Client -> server.
    byte[] toServer = {(byte) 0x68, 0x04, 0x07, 0x00, 0x00, 0x00};
    client.send(Unpooled.copiedBuffer(toServer)).toCompletableFuture().join();

    // Server -> client.
    byte[] toClient = {(byte) 0x68, 0x04, 0x0b, 0x00, 0x00, 0x00};
    connection.send(Unpooled.copiedBuffer(toClient)).toCompletableFuture().join();

    assertEquals(1, serverListener.frames.size());
    assertArrayEquals(toServer, serverListener.frames.get(0));

    assertEquals(1, clientListener.frames.size());
    assertArrayEquals(toClient, clientListener.frames.get(0));

    client.disconnect().toCompletableFuture().join();
  }
}
