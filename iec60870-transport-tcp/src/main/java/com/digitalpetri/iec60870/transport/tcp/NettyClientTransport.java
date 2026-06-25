package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.fsm.FsmContext;
import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.TransportListener;
import com.digitalpetri.netty.fsm.ChannelActions;
import com.digitalpetri.netty.fsm.ChannelFsm;
import com.digitalpetri.netty.fsm.ChannelFsmConfig;
import com.digitalpetri.netty.fsm.ChannelFsmFactory;
import com.digitalpetri.netty.fsm.Event;
import com.digitalpetri.netty.fsm.State;
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
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Netty-backed {@link ClientTransport} for a single outgoing IEC 60870-5-104 connection.
 *
 * <p>The channel lifecycle is delegated to {@code com.digitalpetri.netty:netty-channel-fsm}. The
 * FSM was chosen over a bare {@link Bootstrap} because IEC 104 controlling stations are long-lived
 * and benefit from the FSM's connect, lazy-reconnect, and serialized state handling without this
 * class reimplementing that logic. The FSM is configured non-lazy and persistent so a dropped
 * connection is retried automatically; an intentional {@link #disconnect()} fires {@link
 * com.digitalpetri.netty.fsm.Event.Disconnect} and stops reconnection. {@link #closeConnection()}
 * closes only the current channel and leaves the persistent FSM free to reconnect.
 *
 * <p>Each connect attempt builds the fixed pipeline {@code [SslHandler?] -> frameDecoder ->
 * inboundHandler} via {@link Iec104Pipeline}. When TLS is configured, the {@link
 * ChannelActions#connect} future — and therefore the {@link #connect()} stage — completes only
 * after the {@link SslHandler#handshakeFuture() TLS handshake} succeeds, satisfying the contract
 * that {@code connect()} resolves only on a fully-ready secure channel.
 *
 * <p>Inbound frames and connection-loss notifications are delivered to the registered {@link
 * TransportListener} by the terminal {@link InboundFrameHandler}. The listener is held in an {@link
 * AtomicReference} and resolved lazily, so it may be set before or after {@link #connect()}.
 *
 * <p>This transport is thread-safe: lifecycle calls delegate to the internally-synchronized FSM and
 * the listener reference is atomic.
 */
public class NettyClientTransport implements ClientTransport {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyClientTransport.class);

  private final AtomicReference<@Nullable TransportListener> listener = new AtomicReference<>();

  private final ChannelFsm channelFsm;
  private final NettyClientTransportConfig config;
  private final EventLoopGroup eventLoopGroup;
  private final boolean ownsEventLoopGroup;

  /**
   * Creates a client transport from the given configuration.
   *
   * <p>If the configuration supplies a shared {@link EventLoopGroup} it is used as-is and the
   * caller retains ownership; otherwise a private single-threaded {@link NioEventLoopGroup} is
   * created and shut down when this transport is {@link #disconnect() disconnected}.
   *
   * @param config the transport configuration.
   */
  public NettyClientTransport(NettyClientTransportConfig config) {
    this.config = config;

    EventLoopGroup sharedGroup = config.sharedEventLoopGroupOptional().orElse(null);
    if (sharedGroup != null) {
      this.eventLoopGroup = sharedGroup;
      this.ownsEventLoopGroup = false;
    } else {
      this.eventLoopGroup = new NioEventLoopGroup(1);
      this.ownsEventLoopGroup = true;
    }

    ChannelFsmConfig fsmConfig =
        ChannelFsmConfig.newBuilder()
            .setLazy(false)
            .setPersistent(true)
            .setMaxIdleSeconds(0) // APCI t3 keep-alive is handled by the core session, not the FSM.
            .setChannelActions(new Iec104ChannelActions())
            .setLoggerName(NettyClientTransport.class.getName() + "." + config.host())
            .build();

    this.channelFsm = new ChannelFsmFactory(fsmConfig).newChannelFsm();
  }

  @Override
  public CompletionStage<Void> connect() {
    return channelFsm.connect().thenAccept(channel -> {});
  }

  @Override
  public CompletionStage<Void> disconnect() {
    return channelFsm
        .disconnect()
        .whenComplete(
            (v, ex) -> {
              if (ownsEventLoopGroup) {
                eventLoopGroup.shutdownGracefully();
              }
            });
  }

  @Override
  public void closeConnection() {
    channelFsm
        .getChannel(false)
        .whenComplete(
            (channel, ex) -> {
              if (channel != null) {
                channel.close();
              } else if (ex != null) {
                LOGGER.debug("no active channel to close", ex);
              }
            });
  }

  @Override
  public boolean isConnected() {
    return channelFsm.getState() == State.Connected;
  }

  @Override
  public CompletionStage<Void> send(ByteBuf frame) {
    CompletableFuture<Void> result = new CompletableFuture<>();

    channelFsm
        .getChannel()
        .whenComplete(
            (channel, ex) -> {
              if (channel != null) {
                channel
                    .writeAndFlush(frame)
                    .addListener(
                        future -> {
                          if (future.isSuccess()) {
                            // CompletableFuture<Void> can only be completed with null; the
                            // @NotNull on complete() does not apply to a Void result.
                            //noinspection DataFlowIssue
                            result.complete(null);
                          } else {
                            result.completeExceptionally(future.cause());
                          }
                        });
              } else {
                // No channel to write to: writeAndFlush never runs, so release the caller-owned
                // frame here to honor the send() ownership contract and avoid a leak.
                frame.release();
                result.completeExceptionally(new ConnectionClosedException("not connected", ex));
              }
            });

    return result;
  }

  @Override
  public void setListener(TransportListener listener) {
    this.listener.set(listener);
  }

  /**
   * The {@link ChannelActions} the FSM uses to bootstrap and tear down the underlying channel.
   *
   * <p>{@link #connect(FsmContext)} bootstraps a channel, installs the IEC 104 pipeline, and only
   * completes once the channel is connected and — when TLS is enabled — the handshake has
   * succeeded. {@link #disconnect(FsmContext, Channel)} closes the channel.
   */
  private final class Iec104ChannelActions implements ChannelActions {

    @Override
    public CompletableFuture<Channel> connect(FsmContext<State, Event> ctx) {
      CompletableFuture<Channel> future = new CompletableFuture<>();

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
                    protected void initChannel(Channel channel) {
                      Iec104Pipeline.configure(
                          channel,
                          config.tlsOptionsOptional().orElse(null),
                          true,
                          listener::get,
                          config.host(),
                          config.port());
                    }
                  });

      config.bootstrapCustomizerOptional().ifPresent(customizer -> customizer.accept(bootstrap));

      InetSocketAddress remote = InetSocketAddress.createUnresolved(config.host(), config.port());

      ChannelFuture connectFuture =
          config.localBindOptional().isPresent()
              ? bootstrap.connect(remote, config.localBindOptional().get())
              : bootstrap.connect(remote);

      connectFuture.addListener(
          f -> {
            if (!f.isSuccess()) {
              future.completeExceptionally(f.cause());
              return;
            }

            Channel channel = connectFuture.channel();
            SslHandler sslHandler = channel.pipeline().get(SslHandler.class);

            // ChannelPipeline.get(Class) returns null when no such handler is installed; the
            // SslHandler is only present when TLS is configured. The IDE infers non-null from the
            // unannotated generic return, so suppress the false "always false" report.
            //noinspection ConstantValue
            if (sslHandler == null) {
              future.complete(channel);
            } else {
              awaitHandshake(channel, sslHandler, future);
            }
          });

      return future;
    }

    @Override
    public CompletableFuture<Void> disconnect(FsmContext<State, Event> ctx, Channel channel) {
      CompletableFuture<Void> future = new CompletableFuture<>();

      // CompletableFuture<Void> can only be completed with null; the @NotNull on complete()
      // does not apply to a Void result.
      //noinspection DataFlowIssue
      channel.close().addListener(f -> future.complete(null));

      return future;
    }

    /**
     * Completes {@code future} only after the TLS handshake on {@code channel} succeeds.
     *
     * @param channel the connected channel.
     * @param sslHandler the channel's SSL handler.
     * @param future the future to complete with the ready channel or the handshake failure.
     */
    private void awaitHandshake(
        Channel channel, SslHandler sslHandler, CompletableFuture<Channel> future) {

      Future<Channel> handshakeFuture = sslHandler.handshakeFuture();
      handshakeFuture.addListener(
          f -> {
            if (f.isSuccess()) {
              LOGGER.debug("TLS handshake complete on {}", channel);
              future.complete(channel);
            } else {
              LOGGER.debug("TLS handshake failed on {}: {}", channel, f.cause().toString());
              channel.close();
              future.completeExceptionally(f.cause());
            }
          });
    }
  }
}
