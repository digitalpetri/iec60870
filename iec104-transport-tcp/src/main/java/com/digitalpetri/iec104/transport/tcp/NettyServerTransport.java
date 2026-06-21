package com.digitalpetri.iec104.transport.tcp;

import com.digitalpetri.iec104.transport.ServerTransport;
import com.digitalpetri.iec104.transport.ServerTransportConnection;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Netty-backed {@link ServerTransport} that accepts inbound IEC 60870-5-104 connections.
 *
 * <p>A {@link ServerBootstrap} binds the listening endpoint. Each accepted child channel is given
 * the fixed pipeline {@code [SslHandler?] -> frameDecoder -> frameEncoder -> inboundHandler} via
 * {@link Iec104Pipeline}, wrapped in a {@link NettyServerConnection}, and delivered to the
 * registered connection handler. Accepted channels are tracked in a {@link ChannelGroup} so {@link
 * #unbind()} can close the listening channel and every child channel.
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

  private final ChannelGroup childChannels =
      new DefaultChannelGroup("iec104-server-children", GlobalEventExecutor.INSTANCE);

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
  public CompletionStage<Void> bind() {
    CompletableFuture<Void> result = new CompletableFuture<>();

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

    bootstrap
        .bind(bindAddress)
        .addListener(
            (ChannelFuture future) -> {
              if (future.isSuccess()) {
                listenChannel.set(future.channel());
                LOGGER.debug("bound IEC 104 server on {}", bindAddress);
                // null is the only valid completion value for a CompletableFuture<Void>.
                //noinspection DataFlowIssue
                result.complete(null);
              } else {
                result.completeExceptionally(future.cause());
              }
            });

    return result;
  }

  @Override
  public CompletionStage<Void> unbind() {
    CompletableFuture<Void> result = new CompletableFuture<>();

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
                                  // null is the only valid completion value for a
                                  // CompletableFuture<Void>.
                                  //noinspection DataFlowIssue
                                  result.complete(null);
                                }));

    if (channel != null) {
      channel.close().addListener(f -> closeChildrenAndGroups.run());
    } else {
      closeChildrenAndGroups.run();
    }

    return result;
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
    if (childChannels.size() >= config.maxConnections()) {
      LOGGER.debug(
          "rejecting connection from {}; maxConnections={} reached",
          channel.remoteAddress(),
          config.maxConnections());
      channel.close();
      return;
    }

    childChannels.add(channel);

    NettyServerConnection connection = new NettyServerConnection(channel);

    Iec104Pipeline.configure(
        channel,
        config.profile(),
        config.tlsOptionsOptional().orElse(null),
        false,
        connection::listener);

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
