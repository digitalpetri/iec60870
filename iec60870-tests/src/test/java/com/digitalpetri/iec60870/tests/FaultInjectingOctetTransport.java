package com.digitalpetri.iec60870.tests;

import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * A fault-capable sibling of {@link LoopbackOctetTransport}: an in-JVM client+server
 * octet-transport pair that relays whole-frame {@link ByteBuf}s like the loopback, but routes every
 * frame through a scriptable, per-direction {@link FaultPolicy} so abnormal-path behavior can be
 * driven deterministically without TCP or wall-clock timing.
 *
 * <p>This is test infrastructure for exercising the {@code Apdu}/APCI session engine against
 * adverse transports: dropped, corrupted, duplicated, reordered, partitioned, stalled, and
 * abruptly-killed connections. Every fault is triggered by an explicit control-API call (or a frame
 * count), never by elapsed time, so tests stay reproducible. Delivery ordering that a real network
 * would govern by latency is instead governed here by {@link #release(Direction)} on held frames.
 *
 * <p><b>Buffer ownership.</b> Like the loopback, the pair honors the SPI's reference-counting
 * contract on both legs. Each {@code send(frame)} takes ownership of the caller's {@code frame}.
 * Whatever the fault policy decides, the caller's {@code frame} is released exactly once on the way
 * through:
 *
 * <ul>
 *   <li><b>relay / corrupt / duplicate</b> — the payload is copied into one (or two) fresh buffers
 *       (mirroring a real transport writing to the wire and re-reading inbound), the copy is handed
 *       to the peer's {@link TransportListener#onFrame(ByteBuf)} and released after the synchronous
 *       decode, and the caller's {@code frame} is released;
 *   <li><b>drop / partition</b> — no copy is delivered; the caller's {@code frame} is still
 *       released immediately;
 *   <li><b>hold / stall</b> — the payload is copied into a fresh buffer that is <em>retained</em>
 *       in a queue, and the caller's {@code frame} is released immediately. The retained copy is
 *       delivered (and released) when the test calls {@link #release(Direction)} or {@link
 *       #resume(Direction)}, or is released without delivery when {@link #drainAndRelease()} /
 *       {@link #kill()} discards the queue.
 * </ul>
 *
 * <p>Tests that exercise this transport should run under {@link ParanoidLeakDetection} (on by
 * default in this module) and, on teardown, call {@link #drainAndRelease()} to release any frames
 * still parked in hold/stall queues so a deliberately-held frame is never reported as a leak.
 */
final class FaultInjectingOctetTransport {

  /** A relay direction across the in-JVM pair. */
  enum Direction {
    /** Frames sent by the client, delivered to the server connection's listener. */
    CLIENT_TO_SERVER,
    /** Frames sent by the server connection, delivered to the client's listener. */
    SERVER_TO_CLIENT
  }

  private final FaultInjectingClientTransport client = new FaultInjectingClientTransport();
  private final FaultInjectingServerTransport server = new FaultInjectingServerTransport();

  private final FaultPolicy clientToServer = new FaultPolicy(Direction.CLIENT_TO_SERVER);
  private final FaultPolicy serverToClient = new FaultPolicy(Direction.SERVER_TO_CLIENT);

  /**
   * Returns the client end of the fault-injecting pair.
   *
   * @return the {@link ClientTransport} leg.
   */
  ClientTransport client() {
    return client;
  }

  /**
   * Returns the server end of the fault-injecting pair.
   *
   * @return the {@link ServerTransport} leg.
   */
  ServerTransport server() {
    return server;
  }

  // ---------------------------------------------------------------------------------------------
  // Control API
  // ---------------------------------------------------------------------------------------------

  /**
   * Schedules the next {@code n} frames in {@code direction} to be silently dropped (swallowed and
   * released, never delivered). After {@code n} frames have been dropped, delivery returns to
   * normal.
   *
   * @param direction the direction whose frames to drop.
   * @param n the number of upcoming frames to drop; must be non-negative.
   */
  void dropNext(Direction direction, int n) {
    policy(direction).dropNext(n);
  }

  /**
   * Schedules the next frame in {@code direction} to be corrupted before delivery. The supplied
   * {@code mutator} runs against a writable, defensively-copied frame; whatever it returns (the
   * same buffer mutated, or a fresh shorter buffer to simulate a truncated/short read) is delivered
   * to the peer in place of the original. The mutator must return a buffer the transport then owns.
   *
   * @param direction the direction whose next frame to corrupt.
   * @param mutator transforms the copied frame into the bytes actually delivered.
   */
  void corruptNext(Direction direction, UnaryOperator<ByteBuf> mutator) {
    policy(direction).corruptNext(mutator);
  }

  /**
   * Schedules the next frame in {@code direction} to be delivered twice (the original plus one
   * duplicate copy), simulating a retransmit the peer must tolerate.
   *
   * @param direction the direction whose next frame to duplicate.
   */
  void duplicateNext(Direction direction) {
    policy(direction).duplicateNext();
  }

  /**
   * Schedules the next frame in {@code direction} to be held in a queue instead of delivered. The
   * frame is delivered (in FIFO order with any other held frames) only when {@link
   * #release(Direction)} is called, giving the test deterministic control over delivery delay and
   * ordering without any wall-clock dependence.
   *
   * @param direction the direction whose next frame to hold.
   */
  void holdNext(Direction direction) {
    policy(direction).holdNext();
  }

  /**
   * Delivers the oldest held frame in {@code direction}, if any. Frames held by {@link
   * #holdNext(Direction)} or queued while {@link #stall(Direction)} is active are delivered in FIFO
   * order, one per call.
   *
   * @param direction the direction to release one held frame in.
   * @return {@code true} if a held frame was delivered, {@code false} if the queue was empty.
   */
  boolean release(Direction direction) {
    return policy(direction).releaseOne();
  }

  /**
   * Partitions {@code direction}: every frame sent in that direction is dropped (and released)
   * until {@link #heal(Direction)} is called. Models a one-way network partition where the sender's
   * writes succeed locally but nothing reaches the peer.
   *
   * @param direction the direction to partition.
   */
  void partition(Direction direction) {
    policy(direction).partition();
  }

  /**
   * Heals a {@link #partition(Direction) partition} in {@code direction}, restoring normal delivery
   * for subsequently-sent frames.
   *
   * @param direction the direction to heal.
   */
  void heal(Direction direction) {
    policy(direction).heal();
  }

  /**
   * Stalls the write side of {@code direction}: frames are copied and queued but not delivered,
   * modelling a peer that has stopped reading so the sender's {@code k}-window fills with
   * unacknowledged frames. Queued frames are delivered when {@link #resume(Direction)} is called.
   *
   * @param direction the direction to stall.
   */
  void stall(Direction direction) {
    policy(direction).stall();
  }

  /**
   * Resumes a {@link #stall(Direction) stalled} direction, draining every queued frame to the peer
   * in FIFO order, then restoring normal delivery.
   *
   * @param direction the direction to resume and drain.
   */
  void resume(Direction direction) {
    policy(direction).resume();
  }

  /**
   * Abruptly drops the connection (half-open style): fires {@link
   * TransportListener#onConnectionLost(Throwable)} on both peers, discards and releases any held or
   * stalled frames, and marks the pair disconnected so subsequent sends fail. No graceful close
   * handshake occurs, mirroring a yanked cable.
   */
  void kill() {
    clientToServer.drainAndRelease();
    serverToClient.drainAndRelease();
    client.fail(new IllegalStateException("connection killed"));
  }

  /**
   * Releases every frame still parked in a hold or stall queue, without delivering it. Intended for
   * test teardown so a deliberately-held frame is not reported as a leak.
   */
  void drainAndRelease() {
    clientToServer.drainAndRelease();
    serverToClient.drainAndRelease();
  }

  private FaultPolicy policy(Direction direction) {
    return direction == Direction.CLIENT_TO_SERVER ? clientToServer : serverToClient;
  }

  // ---------------------------------------------------------------------------------------------
  // Fault policy
  // ---------------------------------------------------------------------------------------------

  /**
   * The scriptable per-direction policy. Owns the held/stalled queue for its direction and resolves
   * the peer listener lazily so it works whether or not the connection is currently up.
   *
   * <p>Every method that consumes a caller frame releases it exactly once.
   */
  private final class FaultPolicy {

    private final Direction direction;
    private final Deque<ByteBuf> queued = new ArrayDeque<>();

    private int dropCount;
    private boolean partitioned;
    private boolean stalled;
    private boolean holdNext;
    private boolean duplicateNext;
    private @Nullable UnaryOperator<ByteBuf> corruptNext;

    FaultPolicy(Direction direction) {
      this.direction = direction;
    }

    void dropNext(int n) {
      if (n < 0) {
        throw new IllegalArgumentException("n must be >= 0");
      }
      dropCount += n;
    }

    void corruptNext(UnaryOperator<ByteBuf> mutator) {
      this.corruptNext = mutator;
    }

    void duplicateNext() {
      this.duplicateNext = true;
    }

    void holdNext() {
      this.holdNext = true;
    }

    void partition() {
      this.partitioned = true;
    }

    void heal() {
      this.partitioned = false;
    }

    void stall() {
      this.stalled = true;
    }

    void resume() {
      this.stalled = false;
      while (!queued.isEmpty()) {
        deliverAndRelease(queued.pollFirst());
      }
    }

    boolean releaseOne() {
      ByteBuf held = queued.pollFirst();
      if (held == null) {
        return false;
      }
      deliverAndRelease(held);
      return true;
    }

    void drainAndRelease() {
      ByteBuf held;
      while ((held = queued.pollFirst()) != null) {
        held.release();
      }
    }

    /**
     * Routes one outbound caller {@code frame} through the policy, releasing it exactly once.
     *
     * @return a completed stage on accept, or a failed stage if the peer is not connected.
     */
    CompletionStage<Void> send(ByteBuf frame) {
      try {
        TransportListener peer = peerListener();
        if (peer == null) {
          return CompletableFuture.failedFuture(new IllegalStateException("peer not connected"));
        }

        // Drop / partition: swallow, deliver nothing, but still release the caller frame.
        if (partitioned || dropCount > 0) {
          if (dropCount > 0) {
            dropCount--;
          }
          return CompletableFuture.completedFuture(null);
        }

        // Corrupt: mutate a fresh copy and deliver that instead of the original bytes.
        UnaryOperator<ByteBuf> mutator = corruptNext;
        if (mutator != null) {
          corruptNext = null;
          ByteBuf corrupted = mutator.apply(frame.copy());
          if (holdNext || stalled) {
            holdNext = false;
            queued.addLast(corrupted);
          } else {
            deliverAndRelease(corrupted);
          }
          return CompletableFuture.completedFuture(null);
        }

        // Hold / stall: park a fresh copy for later, controlled delivery.
        if (holdNext || stalled) {
          holdNext = false;
          queued.addLast(frame.copy());
          return CompletableFuture.completedFuture(null);
        }

        // Normal relay, with optional duplicate (deliver the same payload twice).
        deliverAndRelease(frame.copy());
        if (duplicateNext) {
          duplicateNext = false;
          deliverAndRelease(frame.copy());
        }
        return CompletableFuture.completedFuture(null);
      } finally {
        frame.release();
      }
    }

    /** Hands one already-owned {@code delivered} buffer to the peer and releases it. */
    private void deliverAndRelease(ByteBuf delivered) {
      try {
        TransportListener peer = peerListener();
        if (peer != null) {
          peer.onFrame(delivered);
        }
      } finally {
        delivered.release();
      }
    }

    private @Nullable TransportListener peerListener() {
      return direction == Direction.CLIENT_TO_SERVER
          ? server.connectionListener()
          : client.listener();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Transport legs
  // ---------------------------------------------------------------------------------------------

  /** The client leg of the fault-injecting pair. */
  private final class FaultInjectingClientTransport implements ClientTransport {

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
      closeCurrentConnection(null);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void closeConnection() {
      closeCurrentConnection(null);
    }

    @Override
    public boolean isConnected() {
      return connected;
    }

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      if (!connected) {
        frame.release();
        return CompletableFuture.failedFuture(new IllegalStateException("not connected"));
      }
      return clientToServer.send(frame);
    }

    @Override
    public void setListener(TransportListener listener) {
      this.listener = listener;
    }

    @Nullable TransportListener listener() {
      return listener;
    }

    /** Abruptly tears down the connection, notifying both peers' listeners of the loss. */
    void fail(@Nullable Throwable cause) {
      closeCurrentConnection(cause);
    }

    private void closeCurrentConnection(@Nullable Throwable cause) {
      if (connected) {
        connected = false;
        notifyLost(cause);
      }
    }

    private void notifyLost(@Nullable Throwable cause) {
      TransportListener serverListener = server.connectionListener();
      if (serverListener != null) {
        serverListener.onConnectionLost(cause);
      }
      TransportListener clientListener = listener;
      if (clientListener != null) {
        clientListener.onConnectionLost(cause);
      }
    }
  }

  /** The server leg of the fault-injecting pair, surfacing a single accepted connection. */
  private final class FaultInjectingServerTransport implements ServerTransport {

    private @Nullable Consumer<ServerTransportConnection> onAccept;
    private @Nullable FaultInjectingServerConnection connection;

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
      FaultInjectingServerConnection accepted =
          new FaultInjectingServerConnection(new InetSocketAddress("loopback", 2404));
      this.connection = accepted;
      handler.accept(accepted);
    }

    @Nullable TransportListener connectionListener() {
      FaultInjectingServerConnection current = connection;
      return current == null ? null : current.listener();
    }
  }

  /** A single accepted connection on the server leg. */
  private final class FaultInjectingServerConnection implements ServerTransportConnection {

    private final SocketAddress remoteAddress;
    private @Nullable TransportListener listener;

    FaultInjectingServerConnection(SocketAddress remoteAddress) {
      this.remoteAddress = remoteAddress;
    }

    @Nullable TransportListener listener() {
      return listener;
    }

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      return serverToClient.send(frame);
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
