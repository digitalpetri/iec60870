package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.ConnectionClosedException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/**
 * Writes frames to a channel in the order they are submitted, whichever thread submits them.
 *
 * <p>Netty writes immediately when called on the channel's event loop and queues a task when called
 * from any other thread. A session that emits frames under its lock from both kinds of thread would
 * then see a later frame written on the event loop overtake an earlier one still queued from
 * another thread, and the peer would reject the I-frame sequence. Submitting every write as an
 * event-loop task keeps the wire order equal to the submission order.
 */
final class OrderedFrameWriter {

  private OrderedFrameWriter() {}

  /**
   * Writes and flushes one frame after every frame submitted before it.
   *
   * @param channel the channel to write to.
   * @param frame the frame; ownership transfers to this method.
   * @return a stage that completes when the frame has been written, or fails if the write fails.
   */
  static CompletionStage<Void> writeAndFlush(Channel channel, ByteBuf frame) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    try {
      channel
          .eventLoop()
          .execute(
              () ->
                  channel
                      .writeAndFlush(frame)
                      .addListener(
                          future -> {
                            if (future.isSuccess()) {
                              // CompletableFuture<Void> can only be completed with null; Netty's
                              // listener has no @Nullable metadata, so the IDE flags it.
                              //noinspection DataFlowIssue
                              result.complete(null);
                            } else {
                              result.completeExceptionally(future.cause());
                            }
                          }));
    } catch (RejectedExecutionException e) {
      frame.release();
      result.completeExceptionally(new ConnectionClosedException("event loop shut down", e));
    }
    return result;
  }
}
