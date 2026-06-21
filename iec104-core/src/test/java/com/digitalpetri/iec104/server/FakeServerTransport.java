package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.apci.Apdu;
import com.digitalpetri.iec104.apci.ControlField;
import com.digitalpetri.iec104.apci.UFunction;
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.transport.ServerTransport;
import com.digitalpetri.iec104.transport.ServerTransportConnection;
import com.digitalpetri.iec104.transport.TransportListener;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * An in-memory {@link ServerTransport} for server unit tests.
 *
 * <p>The test binds the transport, then calls {@link #accept(String)} to simulate a connected
 * controlling station. The returned {@link FakeConnection} captures every APDU the server sends and
 * lets the test inject inbound APDUs and application ASDUs with valid sequence numbers.
 */
final class FakeServerTransport implements ServerTransport {

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
  boolean isBound() {
    return bound;
  }

  /**
   * Simulates a newly accepted connection from the given host.
   *
   * @param host the peer host name used in the remote address.
   * @return the fake connection.
   */
  FakeConnection accept(String host) {
    FakeConnection connection = new FakeConnection(new InetSocketAddress(host, 2404));
    Consumer<ServerTransportConnection> handler = onAccept;
    if (handler == null) {
      throw new IllegalStateException("no connection handler registered");
    }
    handler.accept(connection);
    return connection;
  }

  /** A fake accepted connection that records sent APDUs and injects inbound ones. */
  static final class FakeConnection implements ServerTransportConnection {

    private final SocketAddress remoteAddress;
    private final List<Apdu> sent = new ArrayList<>();
    private @Nullable TransportListener listener;
    private boolean closed;
    private int peerSendSequence;

    FakeConnection(SocketAddress remoteAddress) {
      this.remoteAddress = remoteAddress;
    }

    @Override
    public CompletionStage<Void> send(Apdu apdu) {
      sent.add(apdu);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setListener(TransportListener listener) {
      this.listener = listener;
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public SocketAddress remoteAddress() {
      return remoteAddress;
    }

    @Override
    public Optional<Certificate> peerCertificate() {
      return Optional.empty();
    }

    /** Performs the STARTDT handshake so the connection enters the started state. */
    void startDataTransfer() {
      deliver(new Apdu(new ControlField.TypeU(UFunction.STARTDT_ACT), null));
    }

    /**
     * Delivers an inbound APDU to the server's listener.
     *
     * @param apdu the APDU to deliver.
     */
    void deliver(Apdu apdu) {
      requireListener().onApdu(apdu);
    }

    /**
     * Delivers an application ASDU as an I-frame with valid sequence numbers.
     *
     * @param asdu the ASDU to deliver.
     */
    void deliverAsdu(Asdu asdu) {
      int ns = peerSendSequence++;
      deliver(new Apdu(new ControlField.TypeI(ns, 0), asdu));
    }

    /** Signals connection loss to the server's listener. */
    @SuppressWarnings("unused") // test-fixture affordance mirroring the real transport's API
    void loseConnection() {
      requireListener().onConnectionLost(new RuntimeException("connection lost"));
    }

    /**
     * Reports whether {@link #close()} has been called.
     *
     * @return {@code true} if closed.
     */
    @SuppressWarnings("unused") // test-fixture affordance mirroring the real transport's API
    boolean isClosed() {
      return closed;
    }

    /**
     * Returns the application ASDUs the server has sent so far.
     *
     * @return the sent ASDUs in order.
     */
    List<Asdu> sentAsdus() {
      List<Asdu> asdus = new ArrayList<>();
      for (Apdu apdu : sent) {
        if (apdu.control() instanceof ControlField.TypeI && apdu.asdu() != null) {
          asdus.add(apdu.asdu());
        }
      }
      return asdus;
    }

    private TransportListener requireListener() {
      TransportListener current = listener;
      if (current == null) {
        throw new IllegalStateException("no listener registered");
      }
      return current;
    }
  }
}
