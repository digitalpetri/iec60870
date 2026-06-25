package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The terminal inbound pipeline handler that forwards each whole-frame {@link ByteBuf} to a {@link
 * TransportListener} and reports connection loss exactly once.
 *
 * <p>This handler sits at the end of the pipeline, downstream of the {@link Iec104FrameDecoder}.
 * For every complete frame it invokes {@link TransportListener#onFrame(ByteBuf)}. Because this
 * handler extends {@link SimpleChannelInboundHandler} with auto-release enabled, the frame is
 * released once {@code onFrame} returns: the transport owns the buffer and the listener must decode
 * it synchronously without retaining it, matching the SPI's inbound buffer-ownership contract. When
 * the channel goes inactive or an exception reaches the tail of the pipeline it invokes {@link
 * TransportListener#onConnectionLost(Throwable)} once, guarding against duplicate notifications so
 * a channel error followed by inactivity does not deliver two callbacks.
 *
 * <p>The listener is obtained lazily through a {@link Supplier} so the owning transport can
 * register (or replace) its listener after the pipeline is built. A {@code null} listener simply
 * drops the inbound frame or loss notification; the transport is responsible for installing a
 * listener before any traffic is expected.
 *
 * <p>Callbacks run on the Netty event-loop thread. As documented on {@link TransportListener}, the
 * protocol layer above is a non-blocking single-writer consumer of these callbacks; user-facing
 * blocking work is hopped to a callback executor by the high-level client and server, never here.
 */
class InboundFrameHandler extends SimpleChannelInboundHandler<ByteBuf> {

  private static final Logger LOGGER = LoggerFactory.getLogger(InboundFrameHandler.class);

  private boolean connectionLostSignaled = false;

  private final Supplier<@Nullable TransportListener> listenerSupplier;

  /**
   * Creates an inbound handler that resolves its listener lazily on each callback.
   *
   * @param listenerSupplier supplies the current {@link TransportListener}, or {@code null} if none
   *     is registered yet.
   */
  InboundFrameHandler(Supplier<@Nullable TransportListener> listenerSupplier) {
    // autoRelease is true: the frame ByteBuf is released after channelRead0 returns, which is why
    // the SPI requires onFrame to decode synchronously and not retain the buffer.
    super(ByteBuf.class, true);

    this.listenerSupplier = listenerSupplier;
  }

  /**
   * Forwards one inbound whole-frame {@link ByteBuf} to the registered listener.
   *
   * @param ctx the channel handler context.
   * @param frame the complete length-delimited frame; released by the framework after this returns.
   */
  @Override
  protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
    TransportListener listener = listenerSupplier.get();
    if (listener != null) {
      listener.onFrame(frame);
    } else {
      LOGGER.debug(
          "dropping inbound frame; no listener registered ({} octets)", frame.readableBytes());
    }
  }

  /**
   * Signals normal connection loss when the channel becomes inactive.
   *
   * @param ctx the channel handler context.
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    signalConnectionLost(null);

    ctx.fireChannelInactive();
  }

  /**
   * Signals connection loss with the offending cause and closes the channel.
   *
   * @param ctx the channel handler context.
   * @param cause the failure that reached the tail of the pipeline.
   */
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    LOGGER.debug("exceptionCaught on {}: {}", ctx.channel(), cause.toString());

    signalConnectionLost(cause);

    ctx.close();
  }

  /**
   * Invokes {@link TransportListener#onConnectionLost(Throwable)} at most once per channel.
   *
   * @param cause the failure that caused the loss, or {@code null} for a normal close.
   */
  private void signalConnectionLost(@Nullable Throwable cause) {
    if (connectionLostSignaled) {
      return;
    }
    connectionLostSignaled = true;

    TransportListener listener = listenerSupplier.get();
    if (listener != null) {
      listener.onConnectionLost(cause);
    }
  }
}
