package com.digitalpetri.iec60870.transport.serial;

import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A serial-backed {@link ServerTransport} for a point-to-point IEC 60870-5-101 outstation.
 *
 * <p>A serial line connects exactly two stations, so this transport accepts a single connection.
 * {@link #bind()} opens the configured port and delivers one {@link SerialServerConnection} to the
 * registered handler — which should be installed with {@link #setConnectionHandler(Consumer)}
 * before binding — then completes once the port is listening. {@link #unbind()} closes the port and
 * the connection.
 *
 * <p>The connection is handed to the handler before the port's reader thread starts, so a handler
 * that registers its {@link com.digitalpetri.iec60870.transport.TransportListener} synchronously
 * misses no inbound frame.
 */
public final class SerialServerTransport implements ServerTransport {

  private static final Logger LOGGER = LoggerFactory.getLogger(SerialServerTransport.class);

  private final AtomicReference<@Nullable Consumer<ServerTransportConnection>> connectionHandler =
      new AtomicReference<>();

  private final SerialPortConfig config;

  private volatile @Nullable SerialServerConnection connection;

  /**
   * Creates a server transport for the given serial port configuration.
   *
   * @param config the serial and FT1.2 framing parameters.
   */
  public SerialServerTransport(SerialPortConfig config) {
    this.config = config;
  }

  @Override
  public CompletionStage<Void> bind() {
    Consumer<ServerTransportConnection> handler = connectionHandler.get();
    if (handler == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("no connection handler registered"));
    }

    SerialServerConnection newConnection = new SerialServerConnection(config.portName());
    this.connection = newConnection;

    // Deliver the connection before the reader thread starts so a handler that registers its
    // listener synchronously cannot miss an inbound frame.
    handler.accept(newConnection);

    try {
      newConnection.open(config);
      LOGGER.debug("serial server listening on {}", config.portName());
      return CompletableFuture.completedFuture(null);
    } catch (IOException | RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public CompletionStage<Void> unbind() {
    SerialServerConnection current = connection;
    connection = null;
    if (current != null) {
      current.close();
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void setConnectionHandler(Consumer<ServerTransportConnection> onAccept) {
    connectionHandler.set(onAccept);
  }
}
