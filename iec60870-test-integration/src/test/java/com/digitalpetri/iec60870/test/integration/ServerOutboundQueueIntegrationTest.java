package com.digitalpetri.iec60870.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.client.ReconnectPolicy;
import com.digitalpetri.iec60870.cs104.ApciDiagnostics;
import com.digitalpetri.iec60870.cs104.ControlField;
import com.digitalpetri.iec60870.diagnostics.Direction;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ServerOutboundQueueIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final int POINT_COUNT = 5_000;

  @Test
  void largeRawInterrogationKeepsEveryPointAndFrameInOrder() throws Exception {
    assertLargeRawInterrogation(POINT_COUNT + 2);
  }

  @Test
  void unboundedQueueAcceptsLargeRawInterrogation() throws Exception {
    assertLargeRawInterrogation(0);
  }

  @Test
  void dropNewestKeepsTheAlreadyQueuedPublication() throws Exception {
    EventLoopGroup clientIo = new NioEventLoopGroup(1);
    var resumeClient = new CountDownLatch(1);
    var dropped = new AtomicInteger();
    var diagnostics =
        new ApciDiagnostics() {
          @Override
          public void onAsduDropped(Direction direction, String reason) {
            if (direction == Direction.OUT) {
              dropped.incrementAndGet();
            }
          }
        };
    PointAddress point = PointAddress.of(1, 100);
    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    point,
                    PointType.SCALED,
                    PointValue.scaled((short) 0),
                    PointCapability.REPORTED))
            .build();
    int port = reservePort();
    try (Iec60870Server server =
            TcpIec104Server.builder()
                .bindAddress("127.0.0.1")
                .port(port)
                .addStation(station)
                .maxOutboundQueue(1)
                .eventQueuePolicy(OutboundQueuePolicy.DROP_NEWEST)
                .diagnostics(remote -> diagnostics)
                .build();
        Iec60870Client client =
            TcpIec104Client.builder()
                .host("127.0.0.1")
                .port(port)
                .eventLoopGroup(clientIo)
                .reconnectPolicy(ReconnectPolicy.disabled())
                .build()) {
      try {
        var events = new EventCollector();
        client.events().subscribe(events);
        server.start();
        client.connect();
        var clientPaused = new CountDownLatch(1);
        Future<?> pause =
            clientIo.submit(
                () -> {
                  clientPaused.countDown();
                  await(resumeClient);
                });
        await(clientPaused);

        // The default k window holds 12 frames. One more fits the queue; the last must be dropped.
        for (short value = 0; value < 14; value++) {
          server.publish(point, PointValue.scaled(value), Cause.SPONTANEOUS);
        }
        assertEquals(1, dropped.get());
        resumeClient.countDown();
        pause.get(5, TimeUnit.SECONDS);

        Await.until(
            "13 published values",
            () ->
                events.events().stream().filter(ClientEvent.PointUpdated.class::isInstance).count()
                    == 13);
        assertEquals(
            IntStream.range(0, 13).mapToObj(i -> (short) i).toList(),
            events.events().stream()
                .filter(ClientEvent.PointUpdated.class::isInstance)
                .map(ClientEvent.PointUpdated.class::cast)
                .map(update -> update.value().value())
                .toList());
        assertTrue(client.isConnected());
      } finally {
        resumeClient.countDown();
      }
    } finally {
      clientIo.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  @Test
  void queueSettingsRejectInvalidValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TcpIec104Server.builder().maxOutboundQueue(-1).build());
    // Deliberately violate the public non-null contract to check validation at the builder
    // boundary.
    //noinspection DataFlowIssue
    assertThrows(
        NullPointerException.class, () -> TcpIec104Server.builder().eventQueuePolicy(null));
  }

  private static void assertLargeRawInterrogation(int maxOutboundQueue) throws Exception {
    EventLoopGroup clientIo = new NioEventLoopGroup(1);
    ExecutorService callbacks = Executors.newSingleThreadExecutor();
    var handlerEntered = new CountDownLatch(1);
    var sendBurst = new CountDownLatch(1);
    var burstQueued = new CompletableFuture<Void>();
    var resumeClient = new CountDownLatch(1);
    var sequences = new ConcurrentLinkedQueue<Integer>();
    var diagnostics =
        new ApciDiagnostics() {
          @Override
          public void onApduReceived(ControlField control, int frameLength) {
            if (control instanceof ControlField.TypeI i) {
              sequences.add(i.sendSequenceNumber());
            }
          }
        };
    var handler =
        new ServerHandler() {
          @Override
          public boolean onRawAsdu(ServerContext context, Asdu request) {
            if (request.type() != AsduType.C_IC_NA_1) {
              return false;
            }
            handlerEntered.countDown();
            try {
              await(sendBurst);
              context.send(asdu(request.type(), Cause.ACTIVATION_CONFIRMATION, request.objects()));
              for (int i = 1; i <= POINT_COUNT; i++) {
                context.send(
                    asdu(
                        AsduType.M_SP_NA_1,
                        Cause.INTERROGATED_BY_STATION,
                        List.of(
                            new SinglePointInformation(
                                InformationObjectAddress.of(i),
                                true,
                                new Qds(false, false, false, false, false)))));
              }
              context.send(asdu(request.type(), Cause.ACTIVATION_TERMINATION, request.objects()));
              burstQueued.complete(null);
            } catch (RuntimeException e) {
              burstQueued.completeExceptionally(e);
              throw e;
            }
            return true;
          }
        };

    int port = reservePort();
    try (Iec60870Server server =
            TcpIec104Server.builder()
                .bindAddress("127.0.0.1")
                .port(port)
                .maxOutboundQueue(maxOutboundQueue)
                .addStation(Station.builder(STATION).build())
                .callbackExecutor(callbacks)
                .handler(handler)
                .build();
        Iec60870Client client =
            TcpIec104Client.builder()
                .host("127.0.0.1")
                .port(port)
                .eventLoopGroup(clientIo)
                .reconnectPolicy(ReconnectPolicy.disabled())
                .diagnostics(diagnostics)
                .build()) {
      try {
        server.start();
        client.connect();
        CompletableFuture<InterrogationResult> response =
            client.interrogateAsync(STATION).toCompletableFuture();
        await(handlerEntered);

        // Hold acknowledgements until the entire callback reply has entered the session queue.
        var clientPaused = new CountDownLatch(1);
        Future<?> pause =
            clientIo.submit(
                () -> {
                  clientPaused.countDown();
                  await(resumeClient);
                });
        await(clientPaused);
        sendBurst.countDown();
        burstQueued.get(5, TimeUnit.SECONDS);
        resumeClient.countDown();
        pause.get(5, TimeUnit.SECONDS);

        InterrogationResult result = response.get(10, TimeUnit.SECONDS);
        assertTrue(result.terminated());
        assertEquals(
            IntStream.rangeClosed(1, POINT_COUNT).mapToObj(i -> PointAddress.of(1, i)).toList(),
            result.pointValues().stream().map(InterrogationResult.PointEntry::address).toList());
        assertTrue(
            result.pointValues().stream()
                .allMatch(point -> Boolean.TRUE.equals(point.value().value())));
        assertEquals(IntStream.range(0, POINT_COUNT + 2).boxed().toList(), List.copyOf(sequences));
        assertTrue(client.isConnected());
        client.stopDataTransfer();
        client.startDataTransfer();
        assertTrue(client.isConnected());
      } finally {
        sendBurst.countDown();
        resumeClient.countDown();
      }
    } finally {
      callbacks.shutdownNow();
      assertTrue(callbacks.awaitTermination(5, TimeUnit.SECONDS));
      clientIo.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  private static Asdu asdu(AsduType type, Cause cause, List<InformationObject> objects) {
    return new Asdu(type, false, cause, false, false, OriginatorAddress.none(), STATION, objects);
  }

  private static int reservePort() throws IOException {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for test coordination");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while coordinating test", e);
    }
  }
}
