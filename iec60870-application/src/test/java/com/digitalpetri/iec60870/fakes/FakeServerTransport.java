package com.digitalpetri.iec60870.fakes;

import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A neutral in-memory {@link ServerTransport} for server facade unit tests.
 *
 * <p>The test binds the transport, then calls {@link #accept(String)} to simulate a connected
 * controlling station. The facade builds a per-connection session through the supplied factory; the
 * test's factory attaches the connection's {@link FakeSession} via {@link
 * FakeConnection#attachSession(FakeSession)}. The returned {@link FakeConnection} then delegates
 * inbound delivery, sent-ASDU capture, and data-transfer simulation to that session, and tracks how
 * many times the connection was closed. This fake carries no wire-frame logic.
 */
public final class FakeServerTransport implements ServerTransport {

  private @Nullable Consumer<ServerTransportConnection> onAccept;
  private boolean bound;

  @Override
  public CompletionStage<Void> bind() {
    bound = true;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> unbind() {
    bound = false;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void setConnectionHandler(Consumer<ServerTransportConnection> onAccept) {
    this.onAccept = onAccept;
  }

  /**
   * Reports whether the transport is currently bound.
   *
   * @return {@code true} if bound.
   */
  public boolean isBound() {
    return bound;
  }

  /**
   * Simulates a newly accepted connection from the given host.
   *
   * @param host the peer host name used in the remote address.
   * @return the fake connection.
   */
  public FakeConnection accept(String host) {
    FakeConnection connection = new FakeConnection(new InetSocketAddress(host, 2404));
    Consumer<ServerTransportConnection> handler = onAccept;
    if (handler == null) {
      throw new IllegalStateException("no connection handler registered");
    }
    handler.accept(connection);
    return connection;
  }

  /**
   * A fake accepted connection. Its {@link FakeSession} (attached by the test's session factory)
   * backs inbound delivery and sent-ASDU capture; the connection itself only tracks close calls.
   */
  public static final class FakeConnection implements ServerTransportConnection {

    private final SocketAddress remoteAddress;
    private @Nullable FakeSession session;
    private boolean closed;
    private int closeCount;

    FakeConnection(SocketAddress remoteAddress) {
      this.remoteAddress = remoteAddress;
    }

    /**
     * Attaches the session the facade built for this connection, so the test can drive inbound
     * delivery and read captured ASDUs through the connection handle.
     *
     * @param session the per-connection fake session.
     */
    public void attachSession(FakeSession session) {
      this.session = session;
    }

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      frame.release();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setListener(TransportListener listener) {
      // The facade no longer registers a transport listener; framing and connection-loss routing
      // live in the session assembly. Inert by design.
    }

    @Override
    public void close() {
      closed = true;
      closeCount++;
    }

    @Override
    public SocketAddress remoteAddress() {
      return remoteAddress;
    }

    @Override
    public Optional<Certificate> peerCertificate() {
      return Optional.empty();
    }

    /**
     * Returns how many times {@link #close()} has been invoked, so a test can assert idempotent
     * teardown.
     *
     * @return the number of close() calls.
     */
    public int closeCount() {
      return closeCount;
    }

    /**
     * Reports whether {@link #close()} has been called.
     *
     * @return {@code true} if closed.
     */
    public boolean isClosed() {
      return closed;
    }

    /** Simulates the peer's STARTDT, driving this connection's session into the started state. */
    public void startDataTransfer() {
      session().simulateDataTransferStarted();
    }

    /**
     * Delivers an inbound application ASDU to the facade through the connection's session.
     *
     * @param asdu the ASDU to deliver.
     */
    public void deliverAsdu(Asdu asdu) {
      session().deliverAsdu(asdu);
    }

    /**
     * Simulates a fatal protocol-error self-close on this connection's session, mirroring the path
     * a bad acknowledgement would drive in the real session.
     *
     * @param cause an ASDU the test would otherwise carry; ignored because the session closes
     *     first.
     */
    public void deliverBadAcknowledgement(Asdu cause) {
      Objects.requireNonNull(cause, "cause");
      session().fireClosed(new IllegalStateException("bad acknowledgement"));
    }

    /** Signals connection loss to the facade through the connection's session. */
    public void loseConnection() {
      session().fireClosed(new RuntimeException("connection lost"));
    }

    /**
     * Returns the application ASDUs the server has sent on this connection so far.
     *
     * @return the sent ASDUs in order.
     */
    public List<Asdu> sentAsdus() {
      return session().sentAsdus();
    }

    private FakeSession session() {
      FakeSession current = session;
      if (current == null) {
        throw new IllegalStateException("no session attached");
      }
      return current;
    }
  }
}
