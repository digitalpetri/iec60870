package com.digitalpetri.iec60870.test.common;

import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * An in-JVM loopback pair of the octet transport SPI that relays complete whole-frame {@link
 * ByteBuf}s between a {@link ClientTransport} and a {@link ServerTransport} without any TCP.
 *
 * <p>This is the single shared in-memory octet-transport artifact for the cross-module tests: it
 * lets a suite exercise the {@code Apdu}&lt;-&gt;{@code ByteBuf} framing adapter end to end over
 * the real SPI shapes while staying entirely in process. (Phase 5 reuses this same artifact; do not
 * add a second loopback later.)
 *
 * <p><b>Buffer ownership.</b> The pair honors the SPI's reference-counting contract on both legs.
 * Each {@code send(frame)} takes ownership of the caller's {@code frame}: the relay copies the
 * payload into a fresh buffer (mirroring a real transport writing to the wire and re-reading into a
 * new inbound buffer), releases the caller's {@code frame}, hands the fresh copy to the peer's
 * {@link TransportListener#onFrame(ByteBuf)} where it is decoded synchronously, then releases the
 * copy. The peer listener therefore never owns or retains the buffer, exactly as on a real
 * transport.
 */
public final class LoopbackOctetTransport {

  private final LoopbackClientTransport client = new LoopbackClientTransport();
  private final LoopbackServerTransport server = new LoopbackServerTransport();

  /**
   * Returns the client end of the loopback pair.
   *
   * @return the loopback {@link ClientTransport}.
   */
  public ClientTransport client() {
    return client;
  }

  /**
   * Returns the server end of the loopback pair.
   *
   * @return the loopback {@link ServerTransport}.
   */
  public ServerTransport server() {
    return server;
  }

  /**
   * Relays one frame to {@code peerListener}, taking ownership of {@code frame}.
   *
   * @return a completed stage on delivery, or a failed stage if the peer has no listener.
   */
  private static CompletionStage<Void> relay(
      ByteBuf frame, @Nullable TransportListener peerListener) {
    try {
      if (peerListener == null) {
        return CompletableFuture.failedFuture(new IllegalStateException("peer not connected"));
      }
      ByteBuf delivered = frame.copy();
      try {
        peerListener.onFrame(delivered);
      } finally {
        delivered.release();
      }
      return CompletableFuture.completedFuture(null);
    } finally {
      frame.release();
    }
  }

  /** The client leg of the loopback pair. */
  private final class LoopbackClientTransport implements ClientTransport {

    private @Nullable TransportListener listener;
    private boolean connected;

    @Override
    public CompletionStage<Void> connect() {
      connected = true;
      server.acceptClient();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> disconnect() {
      closeCurrentConnection();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void closeConnection() {
      closeCurrentConnection();
    }

    @Override
    public boolean isConnected() {
      return connected;
    }

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      return relay(frame, server.connectionListener());
    }

    @Override
    public void setListener(TransportListener listener) {
      this.listener = listener;
    }

    @Nullable TransportListener listener() {
      return listener;
    }

    private void closeCurrentConnection() {
      boolean wasConnected = connected;
      connected = false;
      if (wasConnected) {
        TransportListener serverListener = server.connectionListener();
        if (serverListener != null) {
          serverListener.onConnectionLost(null);
        }
        TransportListener clientListener = listener;
        if (clientListener != null) {
          clientListener.onConnectionLost(null);
        }
      }
    }
  }

  /** The server leg of the loopback pair, surfacing a single accepted connection. */
  private final class LoopbackServerTransport implements ServerTransport {

    private @Nullable Consumer<ServerTransportConnection> onAccept;
    private @Nullable LoopbackServerConnection connection;

    @Override
    public CompletionStage<Void> bind() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> unbind() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setConnectionHandler(Consumer<ServerTransportConnection> onAccept) {
      this.onAccept = onAccept;
    }

    void acceptClient() {
      Consumer<ServerTransportConnection> handler = onAccept;
      if (handler == null) {
        throw new IllegalStateException("no connection handler registered");
      }
      LoopbackServerConnection accepted =
          new LoopbackServerConnection(new InetSocketAddress("loopback", 2404));
      this.connection = accepted;
      handler.accept(accepted);
    }

    @Nullable TransportListener connectionListener() {
      LoopbackServerConnection current = connection;
      return current == null ? null : current.listener();
    }
  }

  /** A single accepted loopback connection on the server leg. */
  private final class LoopbackServerConnection implements ServerTransportConnection {

    private final SocketAddress remoteAddress;
    private @Nullable TransportListener listener;

    LoopbackServerConnection(SocketAddress remoteAddress) {
      this.remoteAddress = remoteAddress;
    }

    @Nullable TransportListener listener() {
      return listener;
    }

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      return relay(frame, client.listener());
    }

    @Override
    public void setListener(TransportListener listener) {
      this.listener = listener;
    }

    @Override
    public void close() {
      TransportListener current = listener;
      if (current != null) {
        current.onConnectionLost(null);
      }
    }

    @Override
    public SocketAddress remoteAddress() {
      return remoteAddress;
    }

    @Override
    public Optional<Certificate> peerCertificate() {
      return Optional.empty();
    }
  }
}
