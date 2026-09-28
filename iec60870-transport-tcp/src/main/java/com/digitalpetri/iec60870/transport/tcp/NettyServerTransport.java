package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Netty-backed {@link ServerTransport} that accepts inbound IEC 60870-5-104 connections.
 *
 * <p>A {@link ServerBootstrap} binds the listening endpoint. Each accepted child channel is given
 * the fixed pipeline {@code [SslHandler?] -> frameDecoder -> inboundHandler} via {@link
 * Iec104Pipeline}, wrapped in a {@link NettyServerConnection}, and delivered to the registered
 * connection handler. Accepted channels are tracked in a {@link ChannelGroup} so {@link #unbind()}
 * can close the listening channel and every child channel.
 *
 * <p>If {@link #unbind()} is called while binding, shutdown waits for that bind to finish before
 * closing the endpoint. Repeated bind or unbind calls share the pending transition. A bind
 * attempted during shutdown or after either event loop group starts shutting down fails. If a
 * shared group terminates during a pending bind, its channel is closed and that bind fails. A new
 * bind may be attempted after shutdown when both groups are shared and still running.
 *
 * <p>The {@code maxConnections} cap is enforced at accept time: when the cap is already reached the
 * newly accepted channel is closed before the connection handler sees it.
 *
 * <p>When TLS is configured, the child pipeline's {@link SslHandler} performs the handshake as the
 * first inbound event; the connection is still delivered to the handler immediately so the handler
 * can register its listener, and the {@link NettyServerConnection#peerCertificate() peer
 * certificate} becomes available once the handshake completes.
 */
public class NettyServerTransport implements ServerTransport {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyServerTransport.class);

  private final AtomicReference<@Nullable Consumer<ServerTransportConnection>> connectionHandler =
      new AtomicReference<>();
  private final AtomicReference<@Nullable Channel> listenChannel = new AtomicReference<>();

  // Guarded by this. Keep the bind visible until shutdown has closed its endpoint.
  private @Nullable CompletableFuture<Void> bindResult;
  private @Nullable CompletableFuture<Void> unbindResult;

  private final ChannelGroup childChannels =
      new DefaultChannelGroup("iec60870-server-children", GlobalEventExecutor.INSTANCE);

  /** Atomic admission counter so the {@code maxConnections} cap cannot be overshot under a race. */
  private final AtomicInteger childCount = new AtomicInteger();

  private final NettyServerTransportConfig config;
  private final EventLoopGroup bossGroup;
  private final EventLoopGroup workerGroup;
  private final boolean ownsBossGroup;
  private final boolean ownsWorkerGroup;

  /**
   * Creates a server transport from the given configuration.
   *
   * <p>If the configuration supplies shared boss or worker {@link EventLoopGroup}s they are used
   * as-is and the caller retains ownership; otherwise private {@link NioEventLoopGroup}s are
   * created and shut down on {@link #unbind()}.
   *
   * @param config the transport configuration.
   */
  public NettyServerTransport(NettyServerTransportConfig config) {
    this.config = config;

    EventLoopGroup sharedBoss = config.bossEventLoopGroupOptional().orElse(null);
    if (sharedBoss != null) {
      this.bossGroup = sharedBoss;
      this.ownsBossGroup = false;
    } else {
      this.bossGroup = new NioEventLoopGroup(1);
      this.ownsBossGroup = true;
    }

    EventLoopGroup sharedWorker = config.workerEventLoopGroupOptional().orElse(null);
    if (sharedWorker != null) {
      this.workerGroup = sharedWorker;
      this.ownsWorkerGroup = false;
    } else {
      this.workerGroup = new NioEventLoopGroup();
      this.ownsWorkerGroup = true;
    }
  }

  @Override
  public synchronized CompletionStage<Void> bind() {
    if (bossGroup.isShuttingDown() || workerGroup.isShuttingDown()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("server event loop group is shutting down"));
    }
    if (unbindResult != null && !unbindResult.isDone()) {
      return CompletableFuture.failedFuture(new IllegalStateException("server is unbinding"));
    }
    if (bindResult != null && !bindResult.isCompletedExceptionally()) {
      return bindResult.copy();
    }

    CompletableFuture<Void> result = new CompletableFuture<>();
    bindResult = result;
    try {
      startBind(result);
    } catch (RuntimeException e) {
      result.completeExceptionally(e);
    }
    return result.copy();
  }

  private void startBind(CompletableFuture<Void> result) {
    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(
                new ChannelInitializer<NioSocketChannel>() {
                  @Override
                  protected void initChannel(NioSocketChannel channel) {
                    initChildChannel(channel);
                  }
                });

    config
        .serverBootstrapCustomizerOptional()
        .ifPresent(customizer -> customizer.accept(bootstrap));

    InetSocketAddress bindAddress = new InetSocketAddress(config.bindHost(), config.port());

    ChannelFuture binding = bootstrap.bind(bindAddress);
    // Keep the channel even before binding succeeds. A terminated event loop may never deliver the
    // bind listener, but shutdown must still close a channel registered on a surviving shared loop.
    listenChannel.set(binding.channel());
    AtomicBoolean settled = new AtomicBoolean();
    failBindOnGroupTermination(binding.channel(), settled, result);
    binding.addListener(
        (ChannelFuture future) -> {
          if (!settled.compareAndSet(false, true)) {
            return;
          }
          if (future.isSuccess()) {
            LOGGER.debug("bound IEC 104 server on {}", bindAddress);
            // null is the only valid completion value for a CompletableFuture<Void>.
            //noinspection DataFlowIssue
            result.complete(null);
          } else {
            result.completeExceptionally(future.cause());
          }
        });
  }

  private void failBindOnGroupTermination(
      Channel channel, AtomicBoolean settled, CompletableFuture<Void> result) {
    // Group termination notifications use an executor independent of the terminated I/O loops.
    GenericFutureListener<Future<Object>> terminated =
        ignored -> {
          // Claim the outcome before closing asynchronously, so a late bind cannot report success.
          if (settled.compareAndSet(false, true)) {
            closeChannel(
                channel,
                () ->
                    result.completeExceptionally(
                        new IllegalStateException(
                            "server event loop group terminated while binding")));
          }
        };
    bossGroup.terminationFuture().addListener(terminated);
    workerGroup.terminationFuture().addListener(terminated);
    result.whenComplete(
        (ignored, error) -> {
          bossGroup.terminationFuture().removeListener(terminated);
          workerGroup.terminationFuture().removeListener(terminated);
        });
  }

  @Override
  public synchronized CompletionStage<Void> unbind() {
    if (unbindResult != null && !unbindResult.isDone()) {
      return unbindResult.copy();
    }

    CompletableFuture<Void> result = new CompletableFuture<>();
    unbindResult = result;
    if (bindResult != null) {
      // A failed bind also finishes the transition and must not prevent shutdown.
      bindResult.whenComplete((v, ex) -> closeEndpoint(result));
    } else {
      closeEndpoint(result);
    }
    return result.copy();
  }

  private void closeEndpoint(CompletableFuture<Void> result) {
    Channel channel = listenChannel.getAndSet(null);

    Runnable closeChildrenAndGroups =
        () ->
            childChannels
                .close()
                .addListener(
                    f ->
                        shutdownGroups()
                            .whenComplete(
                                (v, ex) -> {
                                  synchronized (this) {
                                    bindResult = null;
                                  }
                                  // CompletableFuture<Void> only accepts null as a completion
                                  // value.
                                  //noinspection DataFlowIssue
                                  result.complete(null);
                                }));

    if (channel != null) {
      closeChannel(channel, closeChildrenAndGroups);
    } else {
      closeChildrenAndGroups.run();
    }
  }

  private static void closeChannel(Channel channel, Runnable onClosed) {
    if (channel.isOpen()) {
      // A shared loop can terminate between requesting close and adding its listener. Notify the
      // cleanup on an independent executor so that race cannot strand bind or unbind.
      channel
          .close(new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE))
          .addListener(ignored -> onClosed.run());
    } else {
      onClosed.run();
    }
  }

  @Override
  public void setConnectionHandler(Consumer<ServerTransportConnection> onAccept) {
    this.connectionHandler.set(onAccept);
  }

  /**
   * Initializes one accepted child channel: enforces the connection cap, builds the pipeline, and
   * delivers the connection to the handler.
   *
   * @param channel the accepted child channel.
   */
  private void initChildChannel(Channel channel) {
    // Reserve a slot atomically: increment first, roll back on overshoot, so concurrent accepts on
    // different event-loop threads cannot both pass a size() check and exceed the cap.
    if (childCount.incrementAndGet() > config.maxConnections()) {
      childCount.decrementAndGet();
      LOGGER.debug(
          "rejecting connection from {}; maxConnections={} reached",
          channel.remoteAddress(),
          config.maxConnections());
      channel.close();
      return;
    }

    childChannels.add(channel);
    // Free the reserved slot once the channel closes, registered exactly once here at admission.
    channel.closeFuture().addListener(future -> childCount.decrementAndGet());

    NettyServerConnection connection = new NettyServerConnection(channel);

    Iec104Pipeline.configure(
        channel,
        config.tlsOptionsOptional().orElse(null),
        false,
        connection::listener,
        config.frameDecoderFactoryOptional().orElse(null));

    Consumer<ServerTransportConnection> handler = connectionHandler.get();
    if (handler != null) {
      handler.accept(connection);
    } else {
      LOGGER.debug("no connection handler registered; closing {}", channel.remoteAddress());
      channel.close();
    }
  }

  /**
   * Shuts down the boss and worker groups that this transport owns.
   *
   * @return a stage that completes once the owned groups have terminated.
   */
  private CompletionStage<Void> shutdownGroups() {
    CompletableFuture<Void> bossDone = new CompletableFuture<>();
    CompletableFuture<Void> workerDone = new CompletableFuture<>();

    if (ownsBossGroup) {
      // null is the only valid completion value for a CompletableFuture<Void>.
      //noinspection DataFlowIssue
      bossGroup.shutdownGracefully().addListener(f -> bossDone.complete(null));
    } else {
      // null is the only valid completion value for a CompletableFuture<Void>.
      //noinspection DataFlowIssue
      bossDone.complete(null);
    }

    if (ownsWorkerGroup) {
      // null is the only valid completion value for a CompletableFuture<Void>.
      //noinspection DataFlowIssue
      workerGroup.shutdownGracefully().addListener(f -> workerDone.complete(null));
    } else {
      // null is the only valid completion value for a CompletableFuture<Void>.
      //noinspection DataFlowIssue
      workerDone.complete(null);
    }

    return CompletableFuture.allOf(bossDone, workerDone);
  }
}
