package com.digitalpetri.iec60870.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NettyServerTransportLifecycleTest {

  @Test
  void unbindWaitsForPendingBindAndClosesTheEndpoint() throws Exception {
    NioEventLoopGroup group = new NioEventLoopGroup(1);
    CountDownLatch gate = new CountDownLatch(1);
    CompletableFuture<InetSocketAddress> endpoint = new CompletableFuture<>();
    NettyServerTransport transport =
        new NettyServerTransport(
            NettyServerTransportConfig.builder("127.0.0.1", 0)
                .bossEventLoopGroup(group)
                .workerEventLoopGroup(group)
                .serverBootstrapCustomizer(
                    bootstrap ->
                        bootstrap.handler(
                            new ChannelInboundHandlerAdapter() {
                              @Override
                              public void channelActive(ChannelHandlerContext ctx) {
                                endpoint.complete((InetSocketAddress) ctx.channel().localAddress());
                                ctx.fireChannelActive();
                              }
                            }))
                .build());
    try {
      blockEventLoop(group, gate);
      CompletableFuture<Void> binding = transport.bind().toCompletableFuture();
      assertFalse(binding.isDone());

      CompletableFuture<Void> unbinding = transport.unbind().toCompletableFuture();
      boolean completedWhileBindPending = unbinding.isDone();
      gate.countDown();

      binding.get(5, TimeUnit.SECONDS);
      unbinding.get(5, TimeUnit.SECONDS);
      InetSocketAddress address = endpoint.get(5, TimeUnit.SECONDS);
      assertAll(
          () -> assertFalse(completedWhileBindPending, "unbind must include the pending bind"),
          () -> {
            try (Socket socket = new Socket()) {
              assertThrows(ConnectException.class, () -> socket.connect(address, 1000));
            }
          },
          () -> assertFalse(group.isShuttingDown(), "the caller owns shared event loops"));
    } finally {
      gate.countDown();
      transport.unbind().toCompletableFuture().get(5, TimeUnit.SECONDS);
      group.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  void unbindWaitsForFailedBindAndSharesPendingShutdown() throws Exception {
    NioEventLoopGroup group = new NioEventLoopGroup(1);
    CountDownLatch gate = new CountDownLatch(1);
    try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      NettyServerTransport transport =
          new NettyServerTransport(
              NettyServerTransportConfig.builder(
                      occupied.getInetAddress().getHostAddress(), occupied.getLocalPort())
                  .bossEventLoopGroup(group)
                  .workerEventLoopGroup(group)
                  .build());
      try {
        blockEventLoop(group, gate);
        CompletableFuture<Void> binding = transport.bind().toCompletableFuture();
        CompletableFuture<Void> unbinding = transport.unbind().toCompletableFuture();
        CompletableFuture<Void> repeatedUnbinding = transport.unbind().toCompletableFuture();
        assertFalse(unbinding.isDone());
        assertFalse(repeatedUnbinding.isDone());
        gate.countDown();

        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> binding.get(5, TimeUnit.SECONDS));
        assertInstanceOf(BindException.class, failure.getCause());
        unbinding.get(5, TimeUnit.SECONDS);
        repeatedUnbinding.get(5, TimeUnit.SECONDS);
        assertFalse(group.isShuttingDown());
      } finally {
        gate.countDown();
        transport.unbind().toCompletableFuture().get(5, TimeUnit.SECONDS);
      }
    } finally {
      gate.countDown();
      group.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  void lifecycleCallsShareWorkWithoutSharingCancellation() throws Exception {
    NioEventLoopGroup group = new NioEventLoopGroup(1);
    CountDownLatch gate = new CountDownLatch(1);
    AtomicInteger bindAttempts = new AtomicInteger();
    NettyServerTransport transport =
        new NettyServerTransport(
            NettyServerTransportConfig.builder("127.0.0.1", 0)
                .bossEventLoopGroup(group)
                .workerEventLoopGroup(group)
                .serverBootstrapCustomizer(bootstrap -> bindAttempts.incrementAndGet())
                .build());
    try {
      blockEventLoop(group, gate);
      CompletableFuture<Void> binding = transport.bind().toCompletableFuture();
      CompletableFuture<Void> repeatedBinding = transport.bind().toCompletableFuture();
      binding.cancel(false);
      CompletableFuture<Void> unbinding = transport.unbind().toCompletableFuture();
      unbinding.cancel(false);
      CompletableFuture<Void> repeatedUnbinding = transport.unbind().toCompletableFuture();
      assertFalse(repeatedUnbinding.isDone());

      ExecutionException failure =
          assertThrows(
              ExecutionException.class,
              () -> transport.bind().toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertInstanceOf(IllegalStateException.class, failure.getCause());
      gate.countDown();
      repeatedBinding.get(5, TimeUnit.SECONDS);
      repeatedUnbinding.get(5, TimeUnit.SECONDS);
      assertEquals(1, bindAttempts.get());

      transport.bind().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(2, bindAttempts.get());
    } finally {
      gate.countDown();
      transport.unbind().toCompletableFuture().get(5, TimeUnit.SECONDS);
      group.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
    }
  }

  private static void blockEventLoop(NioEventLoopGroup group, CountDownLatch gate)
      throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    group
        .next()
        .execute(
            () -> {
              entered.countDown();
              try {
                gate.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
              }
            });
    assertTrue(entered.await(5, TimeUnit.SECONDS), "event loop did not reach the gate");
  }
}
