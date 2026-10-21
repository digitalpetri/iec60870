package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.diagnostics.ConnectionDiagnostics;
import com.digitalpetri.iec60870.diagnostics.LoggingContext;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslHandler;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Netty-backed {@link ClientTransport} for a single outgoing IEC 60870-5 TCP connection.
 *
 * <p>Each {@link #connect()} makes one connection attempt, bounded by the configured connect
 * timeout and, when TLS is configured, by the TLS handshake timeout. The transport never dials on
 * its own: after a connection is lost, nothing happens until the next {@code connect()}. Retrying
 * is the job of the client facade above it.
 *
 * <p>{@link #disconnect()} and {@link #closeConnection()} close the current connection, and abort
 * an attempt still in progress, without ending the transport; a later {@code connect()} dials
 * again. {@link #close()} is terminal: it closes the connection and shuts down the private event
 * loop group if the transport created one. After {@code close()}, {@code connect()} fails with a
 * {@link ConnectionClosedException}.
 *
 * <p>Each attempt builds the fixed pipeline {@code [SslHandler?] -> frameDecoder -> inboundHandler}
 * via {@link Iec104Pipeline}. The registered {@link TransportListener} receives frames and the loss
 * notification only for the established connection; a connection closed through {@code
 * disconnect()}, {@code closeConnection()}, or {@code close()} is not reported as lost, and neither
 * is an attempt that never completed.
 *
 * <p>{@link #send(ByteBuf)} submits every write as an event-loop task through {@link
 * OrderedFrameWriter}, so frames leave in the order {@code send} was called, whichever thread calls
 * it.
 *
 * <p>{@link #setReadPaused(boolean)} toggles Netty's {@code autoRead} on the current channel, so a
 * paused connection stops reading from its socket and the peer's own flow control throttles it.
 * Each new channel starts with reading enabled.
 *
 * <p>Every attempt is reported to the configured {@link ConnectionDiagnostics}: {@link
 * ConnectionDiagnostics#onConnectAttempt(SocketAddress)} when it starts, then either {@link
 * ConnectionDiagnostics#onConnected(SocketAddress, SocketAddress)} or {@link
 * ConnectionDiagnostics#onConnectFailed(SocketAddress, Throwable)} with the cause, including a
 * failed TLS handshake and an attempt aborted by {@code disconnect()}. Log lines carry the
 * configured logging context.
 *
 * <p>This transport is thread-safe.
 */
public class NettyClientTransport implements ClientTransport {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyClientTransport.class);

  private final AtomicReference<@Nullable TransportListener> listener = new AtomicReference<>();

  private final NettyClientTransportConfig config;
  private final ConnectionDiagnostics diagnostics;
  private final LoggingContext loggingContext;
  private final EventLoopGroup eventLoopGroup;
  private final boolean ownsEventLoopGroup;

  private final Object lock = new Object();

  /** The established connection, if any; written under {@link #lock}. */
  private volatile @Nullable Channel channel;

  /** The attempt in progress, if any; guarded by {@link #lock}. */
  private @Nullable Attempt pending;

  /** Whether {@link #close()} ended this transport; guarded by {@link #lock}. */
  private boolean closed;

  /**
   * Creates a client transport from the given configuration.
   *
   * <p>If the configuration supplies a shared {@link EventLoopGroup} it is used as-is and the
   * caller retains ownership; otherwise a private single-threaded {@link NioEventLoopGroup} is
   * created and shut down by {@link #close()}.
   *
   * @param config the transport configuration.
   */
  public NettyClientTransport(NettyClientTransportConfig config) {
    this.config = config;
    this.diagnostics = ConnectionDiagnostics.of(config.diagnostics());
    this.loggingContext = LoggingContext.of(config.loggingContext());

    EventLoopGroup sharedGroup = config.sharedEventLoopGroupOptional().orElse(null);
    if (sharedGroup != null) {
      this.eventLoopGroup = sharedGroup;
      this.ownsEventLoopGroup = false;
    } else {
      this.eventLoopGroup = new NioEventLoopGroup(1);
      this.ownsEventLoopGroup = true;
    }
  }

  /**
   * Makes one connection attempt, or joins the attempt already in progress.
   *
   * <p>Completes at once when a connection is already established. Fails with a {@link
   * ConnectionClosedException} after {@link #close()}, or when the event loop group is shutting
   * down.
   *
   * @return a stage that completes when the connection is established (and the TLS handshake has
   *     succeeded, if configured), or completes exceptionally if the attempt fails or is aborted.
   */
  @Override
  public CompletionStage<Void> connect() {
    Attempt started;
    synchronized (lock) {
      if (closed) {
        return CompletableFuture.failedFuture(new ConnectionClosedException("transport closed"));
      }
      Channel current = channel;
      if (current != null && current.isActive()) {
        return CompletableFuture.completedFuture(null);
      }
      channel = null;
      if (pending != null) {
        return pending.future.copy();
      }
      if (eventLoopGroup.isShuttingDown()) {
        return CompletableFuture.failedFuture(
            new ConnectionClosedException("event loop group is shut down"));
      }
      started = new Attempt(InetSocketAddress.createUnresolved(config.host(), config.port()));
      pending = started;
    }
    diagnostics.onConnectAttempt(started.remote);

    try {
      dial(started);
    } catch (RuntimeException e) {
      // The bootstrap rejects a connect on an event loop that stopped after the check above.
      finish(started, null, e);
    }
    return started.future.copy();
  }

  @Override
  public CompletionStage<Void> disconnect() {
    Channel current;
    Attempt aborted;
    synchronized (lock) {
      current = channel;
      channel = null;
      aborted = pending;
      pending = null;
    }
    if (aborted != null) {
      aborted.abort();
    }
    if (current == null) {
      return CompletableFuture.completedFuture(null);
    }
    CompletableFuture<Void> done = new CompletableFuture<>();
    // CompletableFuture<Void> can only be completed with null; the @NotNull on complete() does not
    // apply to a Void result.
    //noinspection DataFlowIssue
    current.close().addListener(f -> done.complete(null));
    return done;
  }

  @Override
  public void closeConnection() {
    disconnect();
  }

  /**
   * Closes the connection and releases the transport's resources.
   *
   * <p>Shuts down the event loop group if this transport created it; a caller-supplied group is
   * left running. After this call, {@link #connect()} fails with a {@link
   * ConnectionClosedException}. This method is idempotent.
   */
  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
    }
    disconnect();
    if (ownsEventLoopGroup) {
      eventLoopGroup.shutdownGracefully();
    }
  }

  @Override
  public boolean isConnected() {
    Channel current = channel;
    return current != null && current.isActive();
  }

  @Override
  public CompletionStage<Void> send(ByteBuf frame) {
    Channel current = channel;
    if (current == null) {
      // No channel to write to: release the caller-owned frame here to honor the send() ownership
      // contract and avoid a leak.
      frame.release();
      return CompletableFuture.failedFuture(new ConnectionClosedException("not connected"));
    }

    // Every write goes through the event loop's task queue, so frames leave in submission order
    // even when an event-loop write follows one still queued from another thread.
    return OrderedFrameWriter.writeAndFlush(current, frame);
  }

  @Override
  public void setReadPaused(boolean paused) {
    Channel current = channel;
    if (current != null) {
      // Thread-safe in Netty: clearing autoRead stops the read loop after the current read, and
      // setting it again schedules a read on the channel's event loop.
      current.config().setAutoRead(!paused);
    }
  }

  @Override
  public void setListener(TransportListener listener) {
    this.listener.set(listener);
  }

  /**
   * Starts the TCP connect for {@code attempt} and completes it once the channel is connected and,
   * when TLS is configured, the handshake has succeeded.
   *
   * @param attempt the attempt to complete.
   */
  private void dial(Attempt attempt) {
    // Apply IEC 104 t0 as the TCP connection-establishment timeout. Clamp to int millis because
    // CONNECT_TIMEOUT_MILLIS is an int; the config guarantees a positive Duration.
    int connectTimeoutMillis =
        (int) Math.min(config.connectTimeout().toMillis(), Integer.MAX_VALUE);

    Bootstrap bootstrap =
        new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
            .handler(
                new ChannelInitializer<>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    Iec104Pipeline.configure(
                        ch,
                        config.tlsOptionsOptional().orElse(null),
                        true,
                        // Only the established channel reaches the listener, so a channel that
                        // was closed locally or never finished connecting reports nothing.
                        () -> channel == ch ? listener.get() : null,
                        config.host(),
                        config.port(),
                        config.frameDecoderFactoryOptional().orElse(null),
                        loggingContext);
                  }
                });

    config.bootstrapCustomizerOptional().ifPresent(customizer -> customizer.accept(bootstrap));

    InetSocketAddress remote = attempt.remote;

    ChannelFuture connectFuture =
        config.localBindOptional().isPresent()
            ? bootstrap.connect(remote, config.localBindOptional().get())
            : bootstrap.connect(remote);
    Channel attemptChannel = connectFuture.channel();
    attempt.channel = attemptChannel;

    connectFuture.addListener(
        f -> {
          if (!f.isSuccess()) {
            finish(attempt, null, f.cause());
            return;
          }

          Channel ch = connectFuture.channel();
          SslHandler sslHandler = ch.pipeline().get(SslHandler.class);

          // ChannelPipeline.get(Class) returns null when no such handler is installed; the
          // SslHandler is only present when TLS is configured. The IDE infers non-null from the
          // unannotated generic return, so suppress the false "always false" report.
          //noinspection ConstantValue
          if (sslHandler == null) {
            finish(attempt, ch, null);
          } else {
            sslHandler
                .handshakeFuture()
                .addListener(
                    h -> {
                      if (h.isSuccess()) {
                        loggingContext.debug(LOGGER, "TLS handshake complete on {}", ch);
                        finish(attempt, ch, null);
                      } else {
                        loggingContext.debug(
                            LOGGER, "TLS handshake failed on {}: {}", ch, h.cause().toString());
                        finish(attempt, null, h.cause());
                      }
                    });
          }
        });

    if (attempt.aborted) {
      // disconnect() ran before the channel existed; close it now rather than wait for t0.
      attemptChannel.close();
    }
  }

  /**
   * Completes an attempt, installing its channel as the established connection unless the attempt
   * was aborted or failed.
   *
   * @param attempt the attempt to complete.
   * @param ch the connected channel, or {@code null} if the attempt failed.
   * @param cause the failure, or {@code null} if the attempt succeeded.
   */
  private void finish(Attempt attempt, @Nullable Channel ch, @Nullable Throwable cause) {
    boolean accepted;
    synchronized (lock) {
      boolean current = pending == attempt;
      if (current) {
        pending = null;
      }
      accepted = current && ch != null && cause == null;
      if (accepted) {
        channel = ch;
      }
    }

    boolean first = attempt.reported.compareAndSet(false, true);
    if (accepted) {
      if (first) {
        reportConnected(attempt, Objects.requireNonNull(ch));
      }
      // CompletableFuture<Void> can only be completed with null; the @NotNull on complete() does
      // not apply to a Void result.
      //noinspection DataFlowIssue
      attempt.future.complete(null);
      return;
    }
    Channel attemptChannel = ch != null ? ch : attempt.channel;
    if (attemptChannel != null) {
      attemptChannel.close();
    }
    Throwable failure = cause != null ? cause : new ConnectionClosedException("connect aborted");
    if (first) {
      loggingContext.debug(
          LOGGER,
          "connect to {} failed: {}",
          LoggingContext.format(attempt.remote),
          failure.toString());
      diagnostics.onConnectFailed(attempt.remote, failure);
    }
    attempt.future.completeExceptionally(failure);
  }

  private void reportConnected(Attempt attempt, Channel ch) {
    SocketAddress local = ch.localAddress();
    SocketAddress remote = ch.remoteAddress();
    loggingContext.debug(LOGGER, "connected {} -> {}", local, remote);
    diagnostics.onConnected(
        local != null ? local : attempt.remote, remote != null ? remote : attempt.remote);
  }

  /** One connection attempt and the stage its callers wait on. */
  private static final class Attempt {

    private final CompletableFuture<Void> future = new CompletableFuture<>();

    /** The address being dialed. */
    private final InetSocketAddress remote;

    /** Set once the attempt's outcome has been reported to the diagnostics listener. */
    private final AtomicBoolean reported = new AtomicBoolean();

    /** The attempt's channel, set once the bootstrap has created it. */
    private volatile @Nullable Channel channel;

    /** Set when {@link #disconnect()} aborted this attempt. */
    private volatile boolean aborted;

    private Attempt(InetSocketAddress remote) {
      this.remote = remote;
    }

    /** Fails the attempt and closes its channel, if it has one yet. */
    private void abort() {
      aborted = true;
      future.completeExceptionally(new ConnectionClosedException("connect aborted"));
      Channel ch = channel;
      if (ch != null) {
        ch.close();
      }
    }
  }
}
