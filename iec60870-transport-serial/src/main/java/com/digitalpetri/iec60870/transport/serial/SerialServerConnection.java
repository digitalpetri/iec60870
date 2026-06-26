package com.digitalpetri.iec60870.transport.serial;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single accepted connection of a {@link SerialServerTransport}, wrapping one serial port.
 *
 * <p>A point-to-point serial outstation has exactly one peer, so one instance represents the whole
 * link. The owning {@link SerialServerTransport} delivers it to the registered connection handler,
 * which installs a {@link TransportListener} to receive inbound FT1.2 frames. Outbound frames are
 * written on the calling thread by {@link #send(ByteBuf)}.
 *
 * <p>Because a serial line carries no network peer or TLS context, {@link #remoteAddress()} returns
 * a synthetic address whose string form is the serial port name, and {@link #peerCertificate()} is
 * always empty.
 */
public final class SerialServerConnection implements ServerTransportConnection {

  private static final Logger LOGGER = LoggerFactory.getLogger(SerialServerConnection.class);

  private final AtomicReference<@Nullable TransportListener> listener = new AtomicReference<>();

  private final SocketAddress remoteAddress;
  private final Ft12SerialChannel channel;

  SerialServerConnection(String portName) {
    this.remoteAddress = new SerialPortAddress(portName);
    this.channel = new Ft12SerialChannel(this::dispatchFrame, this::dispatchLoss);
  }

  /**
   * Opens the underlying serial port and starts receiving frames.
   *
   * @param config the serial and FT1.2 framing parameters.
   * @throws IOException if the port cannot be opened.
   */
  void open(SerialPortConfig config) throws IOException {
    channel.open(config);
  }

  @Override
  public CompletionStage<Void> send(ByteBuf frame) {
    if (!channel.isOpen()) {
      frame.release();
      return CompletableFuture.failedFuture(
          new ConnectionClosedException("serial connection is closed"));
    }

    try {
      channel.write(frame);
      return CompletableFuture.completedFuture(null);
    } catch (IOException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public void setListener(TransportListener listener) {
    this.listener.set(listener);
  }

  @Override
  public void close() {
    channel.close();
  }

  @Override
  public SocketAddress remoteAddress() {
    return remoteAddress;
  }

  @Override
  public Optional<Certificate> peerCertificate() {
    return Optional.empty();
  }

  private void dispatchFrame(ByteBuf frame) {
    TransportListener current = listener.get();
    if (current != null) {
      current.onFrame(frame);
    } else {
      LOGGER.debug(
          "dropping inbound frame; no listener registered ({} octets)", frame.readableBytes());
    }
  }

  private void dispatchLoss(@Nullable Throwable cause) {
    TransportListener current = listener.get();
    if (current != null) {
      current.onConnectionLost(cause);
    }
  }

  /** A synthetic {@link SocketAddress} that names the serial port a connection runs over. */
  private static final class SerialPortAddress extends SocketAddress {

    private static final long serialVersionUID = 1L;

    private final String portName;

    SerialPortAddress(String portName) {
      this.portName = portName;
    }

    @Override
    public String toString() {
      return portName;
    }
  }
}
