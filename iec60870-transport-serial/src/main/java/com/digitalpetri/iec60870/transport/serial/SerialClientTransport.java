package com.digitalpetri.iec60870.transport.serial;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A serial-backed {@link ClientTransport} for a single point-to-point IEC 60870-5-101 link.
 *
 * <p>This is the serial peer of the Netty-backed TCP client transport. {@link #connect()} opens the
 * configured serial port and starts a reader thread that delivers complete FT1.2 frames to the
 * registered {@link TransportListener}; {@link #send(ByteBuf)} writes one whole frame on the
 * calling thread. Register a listener with {@link #setListener(TransportListener)} before
 * connecting so no inbound frame is missed.
 *
 * <p>Per the transport SPI, {@link #isConnected()} reflects whether the serial port is open, not
 * whether the FT1.2 link has been established; link availability is surfaced by the protocol layer
 * above. This transport is single-station and does not auto-reconnect: {@link #disconnect()} and
 * {@link #closeConnection()} both close the current port, and a subsequent {@link #connect()} opens
 * a fresh one.
 */
public final class SerialClientTransport implements ClientTransport {

  private static final Logger LOGGER = LoggerFactory.getLogger(SerialClientTransport.class);

  private final AtomicReference<@Nullable TransportListener> listener = new AtomicReference<>();

  private final SerialPortConfig config;

  private volatile @Nullable Ft12SerialChannel channel;

  /**
   * Creates a client transport for the given serial port configuration.
   *
   * @param config the serial and FT1.2 framing parameters.
   */
  public SerialClientTransport(SerialPortConfig config) {
    this.config = config;
  }

  @Override
  public CompletionStage<Void> connect() {
    // Defensively tear down any prior channel before replacing it. If the previous connection was
    // lost on the reader thread it already closed its own port; but an app that calls connect()
    // again from its loss handler, without an intervening disconnect(), must not leak the old
    // channel or be blocked by it holding the OS port open.
    Ft12SerialChannel previous = channel;
    if (previous != null) {
      channel = null;
      previous.close();
    }

    Ft12SerialChannel newChannel = new Ft12SerialChannel(this::dispatchFrame, this::dispatchLoss);
    try {
      newChannel.open(config);
      this.channel = newChannel;
      return CompletableFuture.completedFuture(null);
    } catch (IOException | RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletionStage<Void> disconnect() {
    Ft12SerialChannel current = channel;
    channel = null;
    if (current != null) {
      current.close();
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void closeConnection() {
    Ft12SerialChannel current = channel;
    channel = null;
    if (current != null) {
      current.close();
    }
  }

  @Override
  public boolean isConnected() {
    Ft12SerialChannel current = channel;
    return current != null && current.isOpen();
  }

  @Override
  public CompletionStage<Void> send(ByteBuf frame) {
    Ft12SerialChannel current = channel;
    if (current == null || !current.isOpen()) {
      frame.release();
      return CompletableFuture.failedFuture(
          new ConnectionClosedException("serial transport is not connected"));
    }

    try {
      current.write(frame);
      return CompletableFuture.completedFuture(null);
    } catch (IOException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public void setListener(TransportListener listener) {
    this.listener.set(listener);
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
}
