package com.digitalpetri.iec60870.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * A frame sent on the channel's event loop must not overtake a frame another thread sent first.
 *
 * <p>Each test occupies the channel's only event-loop thread, sends frame 1 from the test thread
 * (which can only queue it), then sends frame 2 from inside the event loop, and checks that the
 * peer reads 1 before 2.
 */
class OrderedFrameWriteTest {

  @Test
  void clientTransportKeepsSubmissionOrderAcrossThreads() throws Exception {
    EventLoopGroup group = new NioEventLoopGroup(1);
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      NettyClientTransport transport =
          new NettyClientTransport(
              NettyClientTransportConfig.builder("127.0.0.1", server.getLocalPort())
                  .sharedEventLoopGroup(group)
                  .build());
      transport.connect().toCompletableFuture().get(5, TimeUnit.SECONDS);
      try (Socket peer = server.accept()) {
        assertSubmissionOrder(group, transport::send, peer);
      } finally {
        transport.disconnect().toCompletableFuture().get(5, TimeUnit.SECONDS);
      }
    } finally {
      group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  @Test
  void serverConnectionKeepsSubmissionOrderAcrossThreads() throws Exception {
    EventLoopGroup worker = new NioEventLoopGroup(1);
    int port;
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = probe.getLocalPort();
    }
    NettyServerTransport transport =
        new NettyServerTransport(
            NettyServerTransportConfig.builder("127.0.0.1", port)
                .workerEventLoopGroup(worker)
                .build());
    CompletableFuture<ServerTransportConnection> accepted = new CompletableFuture<>();
    transport.setConnectionHandler(accepted::complete);
    transport.bind().toCompletableFuture().get(5, TimeUnit.SECONDS);
    try (Socket peer = new Socket()) {
      peer.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
      ServerTransportConnection connection = accepted.get(5, TimeUnit.SECONDS);
      assertSubmissionOrder(worker, connection::send, peer);
    } finally {
      transport.unbind().toCompletableFuture().get(5, TimeUnit.SECONDS);
      worker.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  private static void assertSubmissionOrder(
      EventLoopGroup loop, Function<ByteBuf, CompletionStage<Void>> send, Socket peer)
      throws Exception {
    var firstQueued = new CountDownLatch(1);
    var second = new CompletableFuture<CompletionStage<Void>>();
    loop.execute(
        () -> {
          try {
            firstQueued.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          second.complete(send.apply(frame(2)));
        });

    CompletionStage<Void> first = send.apply(frame(1));
    firstQueued.countDown();
    first.toCompletableFuture().get(5, TimeUnit.SECONDS);
    second.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);

    peer.setSoTimeout(5_000);
    InputStream in = peer.getInputStream();
    assertEquals(1, in.read(), "the frame sent first arrives first");
    assertEquals(2, in.read());
  }

  private static ByteBuf frame(int value) {
    return Unpooled.wrappedBuffer(new byte[] {(byte) value});
  }
}
