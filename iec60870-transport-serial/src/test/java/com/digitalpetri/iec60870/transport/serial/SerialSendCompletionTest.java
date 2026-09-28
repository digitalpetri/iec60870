package com.digitalpetri.iec60870.transport.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class SerialSendCompletionTest {

  @TestFactory
  Stream<DynamicTest> sendWaitsForTheDriverToWriteTheFrame() {
    return Stream.of(Endpoint.values())
        .map(
            endpoint ->
                DynamicTest.dynamicTest(
                    endpoint.name(), () -> sendWaitsForTheDriverToWriteTheFrame(endpoint)));
  }

  private void sendWaitsForTheDriverToWriteTheFrame(Endpoint endpoint) throws Exception {
    try (Fixture fixture = new Fixture(endpoint, Outcome.SUCCESS)) {
      ByteBuf frame = frame();
      CompletableFuture<Void> sent = fixture.send(frame);
      fixture.line.awaitWrite();
      assertFalse(sent.isDone(), "enqueueing the frame must not report a completed write");

      fixture.line.gate.countDown();
      sent.get(5, TimeUnit.SECONDS);
      assertEquals(0, frame.refCnt());
      assertEquals(0, fixture.lossCount.get());
    }
  }

  @TestFactory
  Stream<DynamicTest> writeFailureFailsTheCurrentAndQueuedSends() {
    return Stream.of(Endpoint.values())
        .flatMap(
            endpoint ->
                Stream.of(Outcome.SHORT, Outcome.ERROR, Outcome.THROW)
                    .map(
                        outcome ->
                            DynamicTest.dynamicTest(
                                endpoint + " " + outcome,
                                () ->
                                    writeFailureFailsTheCurrentAndQueuedSends(endpoint, outcome))));
  }

  private void writeFailureFailsTheCurrentAndQueuedSends(Endpoint endpoint, Outcome outcome)
      throws Exception {
    try (Fixture fixture = new Fixture(endpoint, outcome)) {
      ByteBuf current = frame();
      CompletableFuture<Void> first = fixture.send(current);
      fixture.line.awaitWrite();
      ByteBuf queued = frame();
      CompletableFuture<Void> second = fixture.send(queued);
      CompletableFuture<Throwable> firstCallbackError = callbackError(first);
      CompletableFuture<Throwable> secondCallbackError = callbackError(second);
      assertFalse(first.isDone());
      assertFalse(second.isDone());

      fixture.line.gate.countDown();
      Throwable failure = failure(first);
      if (outcome == Outcome.THROW) {
        assertInstanceOf(IllegalStateException.class, failure);
      } else {
        assertInstanceOf(IOException.class, failure);
      }
      failure(second);
      fixture.awaitLoss();
      assertSame(failure, firstCallbackError.get(5, TimeUnit.SECONDS));
      assertSame(failure, secondCallbackError.get(5, TimeUnit.SECONDS));
      assertSame(failure, fixture.lossCause.get());
      assertEquals(1, fixture.line.writeCount.get());
      assertEquals(0, current.refCnt());
      assertEquals(0, queued.refCnt());
      assertEquals(1, fixture.lossCount.get());
    }
  }

  @TestFactory
  Stream<DynamicTest> closeFailsTheCurrentAndQueuedSends() {
    return Stream.of(Endpoint.values())
        .map(
            endpoint ->
                DynamicTest.dynamicTest(
                    endpoint.name(), () -> closeFailsTheCurrentAndQueuedSends(endpoint)));
  }

  private void closeFailsTheCurrentAndQueuedSends(Endpoint endpoint) throws Exception {
    try (Fixture fixture = new Fixture(endpoint, Outcome.SUCCESS)) {
      ByteBuf current = frame();
      CompletableFuture<Void> first = fixture.send(current);
      fixture.line.awaitWrite();
      ByteBuf queued = frame();
      CompletableFuture<Void> second = fixture.send(queued);
      CompletableFuture<Throwable> firstCallbackError = callbackError(first);
      CompletableFuture<Throwable> secondCallbackError = callbackError(second);

      fixture.close();
      assertSame(failure(first), firstCallbackError.get(5, TimeUnit.SECONDS));
      assertSame(failure(second), secondCallbackError.get(5, TimeUnit.SECONDS));
      assertEquals(0, current.refCnt());
      assertEquals(0, queued.refCnt());
      assertEquals(1, fixture.line.writeCount.get());
      assertEquals(1, fixture.lossCount.get());
    }
  }

  @TestFactory
  Stream<DynamicTest> readerLossCompletesOutstandingSendsBeforeReportingLoss() {
    return Stream.of(Endpoint.values())
        .map(
            endpoint ->
                DynamicTest.dynamicTest(
                    endpoint.name(),
                    () -> readerLossCompletesOutstandingSendsBeforeReportingLoss(endpoint)));
  }

  private void readerLossCompletesOutstandingSendsBeforeReportingLoss(Endpoint endpoint)
      throws Exception {
    try (Fixture fixture = new Fixture(endpoint, Outcome.SUCCESS)) {
      ByteBuf current = frame();
      CompletableFuture<Void> first = fixture.send(current);
      fixture.line.awaitWrite();
      ByteBuf queued = frame();
      CompletableFuture<Void> second = fixture.send(queued);
      CompletableFuture<Throwable> firstCallbackError = callbackError(first);
      CompletableFuture<Throwable> secondCallbackError = callbackError(second);
      AtomicBoolean failedBeforeLoss = new AtomicBoolean();
      fixture.onLoss =
          () ->
              failedBeforeLoss.set(
                  first.isCompletedExceptionally() && second.isCompletedExceptionally());

      fixture.channel.runReaderLoopForTesting(fixture.line);

      assertTrue(
          failedBeforeLoss.get(), "old writes must finish before a loss callback can reconnect");
      failure(first);
      failure(second);
      assertInstanceOf(IOException.class, fixture.lossCause.get());
      assertSame(fixture.lossCause.get(), firstCallbackError.get(5, TimeUnit.SECONDS));
      assertSame(fixture.lossCause.get(), secondCallbackError.get(5, TimeUnit.SECONDS));
      assertEquals(0, current.refCnt());
      assertEquals(0, queued.refCnt());
      assertEquals(1, fixture.lossCount.get());
    }
  }

  @TestFactory
  Stream<DynamicTest> closeFromFailedSendSettlesOtherWritesBeforeReturning() {
    return Stream.of(Endpoint.values())
        .map(
            endpoint ->
                DynamicTest.dynamicTest(
                    endpoint.name(),
                    () -> closeFromFailedSendSettlesOtherWritesBeforeReturning(endpoint)));
  }

  private void closeFromFailedSendSettlesOtherWritesBeforeReturning(Endpoint endpoint)
      throws Exception {
    try (Fixture fixture = new Fixture(endpoint, Outcome.ERROR)) {
      ByteBuf current = frame();
      CompletableFuture<Void> first = fixture.send(current);
      fixture.line.awaitWrite();
      ByteBuf queued = frame();
      CompletableFuture<Void> second = fixture.send(queued);
      CompletableFuture<Void> callback =
          first.handle(
              (ignored, error) -> {
                assertTrue(error != null, "the write must fail before closing from its callback");
                // Client reconnect closes the old channel before opening the replacement. A
                // synchronous
                // reconnect from a failed-send callback must settle every old write before that
                // close returns.
                fixture.close();
                assertTrue(second.isCompletedExceptionally());
                assertSame(fixture.lossCause.get(), error);
                return null;
              });

      fixture.line.gate.countDown();
      callback.get(5, TimeUnit.SECONDS);
      fixture.awaitLoss();
      assertInstanceOf(IOException.class, fixture.lossCause.get());
      assertEquals(0, current.refCnt());
      assertEquals(0, queued.refCnt());
      assertEquals(1, fixture.lossCount.get());
    }
  }

  @TestFactory
  Stream<DynamicTest> cancellingTheReturnedStageDoesNotCancelTheWrite() {
    return Stream.of(Endpoint.values())
        .map(
            endpoint ->
                DynamicTest.dynamicTest(
                    endpoint.name(),
                    () -> cancellingTheReturnedStageDoesNotCancelTheWrite(endpoint)));
  }

  private void cancellingTheReturnedStageDoesNotCancelTheWrite(Endpoint endpoint) throws Exception {
    try (Fixture fixture = new Fixture(endpoint, Outcome.SUCCESS)) {
      ByteBuf current = frame();
      CompletableFuture<Void> first = fixture.send(current);
      fixture.line.awaitWrite();
      ByteBuf queued = frame();
      CompletableFuture<Void> second = fixture.send(queued);

      assertTrue(first.cancel(false));
      assertFalse(second.isDone());
      fixture.line.gate.countDown();
      second.get(5, TimeUnit.SECONDS);

      assertTrue(first.isCancelled());
      assertEquals(2, fixture.line.writeCount.get());
      assertEquals(0, current.refCnt());
      assertEquals(0, queued.refCnt());
      assertEquals(0, fixture.lossCount.get());
    }
  }

  @TestFactory
  Stream<DynamicTest> fullQueueRejectsAndReleasesTheFrame() {
    return Stream.of(Endpoint.values())
        .map(
            endpoint ->
                DynamicTest.dynamicTest(
                    endpoint.name(), () -> fullQueueRejectsAndReleasesTheFrame(endpoint)));
  }

  private void fullQueueRejectsAndReleasesTheFrame(Endpoint endpoint) throws Exception {
    try (Fixture fixture = new Fixture(endpoint, Outcome.SUCCESS)) {
      List<ByteBuf> frames = new ArrayList<>();
      List<CompletableFuture<Void>> sends = new ArrayList<>();
      ByteBuf current = frame();
      frames.add(current);
      sends.add(fixture.send(current));
      fixture.line.awaitWrite();
      for (int i = 0; i < 256; i++) {
        ByteBuf queued = frame();
        frames.add(queued);
        sends.add(fixture.send(queued));
      }

      ByteBuf rejected = frame();
      assertInstanceOf(IOException.class, failure(fixture.send(rejected)));
      assertEquals(0, rejected.refCnt());
      fixture.close();
      for (CompletableFuture<Void> send : sends) {
        failure(send);
      }
      for (ByteBuf frame : frames) {
        assertEquals(0, frame.refCnt());
      }
      assertEquals(1, fixture.line.writeCount.get());
    }
  }

  private static ByteBuf frame() {
    return Unpooled.wrappedBuffer(new byte[] {0x10, 0x20});
  }

  private static Throwable failure(CompletableFuture<Void> future) {
    return assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
  }

  private static CompletableFuture<Throwable> callbackError(CompletableFuture<Void> future) {
    CompletableFuture<Throwable> observed = new CompletableFuture<>();
    future.whenComplete(
        (ignored, error) -> {
          if (error != null) {
            observed.complete(error);
          } else {
            observed.completeExceptionally(new AssertionError("expected the send to fail"));
          }
        });
    return observed;
  }

  private enum Endpoint {
    CLIENT,
    SERVER
  }

  private enum Outcome {
    SUCCESS,
    SHORT,
    ERROR,
    THROW
  }

  private static final class Fixture implements AutoCloseable {
    final GatedLine line;
    final Ft12SerialChannel channel;
    final AtomicInteger lossCount = new AtomicInteger();
    final AtomicReference<@Nullable Throwable> lossCause = new AtomicReference<>();
    final CountDownLatch loss = new CountDownLatch(1);
    final Function<ByteBuf, CompletionStage<Void>> sender;
    final Runnable closer;
    volatile Runnable onLoss = () -> {};

    Fixture(Endpoint endpoint, Outcome outcome) throws ReflectiveOperationException {
      line = new GatedLine(outcome);
      TransportListener listener =
          new TransportListener() {
            @Override
            public void onFrame(ByteBuf frame) {}

            @Override
            public void onConnectionLost(@Nullable Throwable cause) {
              lossCount.incrementAndGet();
              lossCause.set(cause);
              onLoss.run();
              loss.countDown();
            }
          };
      if (endpoint == Endpoint.CLIENT) {
        SerialClientTransport client =
            new SerialClientTransport(SerialPortConfig.builder("test").build());
        channel = new Ft12SerialChannel(listener::onFrame, listener::onConnectionLost);
        Field field = SerialClientTransport.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(client, channel);
        sender = client::send;
        closer = () -> client.disconnect().toCompletableFuture().join();
      } else {
        SerialServerConnection connection = new SerialServerConnection("test");
        connection.setListener(listener);
        Field field = SerialServerConnection.class.getDeclaredField("channel");
        field.setAccessible(true);
        channel = (Ft12SerialChannel) field.get(connection);
        sender = connection::send;
        closer = connection::close;
      }
      channel.startWriterForTesting(line);
    }

    CompletableFuture<Void> send(ByteBuf frame) {
      return sender.apply(frame).toCompletableFuture();
    }

    void awaitLoss() throws InterruptedException {
      assertTrue(loss.await(5, TimeUnit.SECONDS), "the transport did not report connection loss");
    }

    @Override
    public void close() {
      closer.run();
    }
  }

  private static final class GatedLine implements Ft12SerialChannel.SerialLine {
    final CountDownLatch gate = new CountDownLatch(1);
    final CountDownLatch entered = new CountDownLatch(1);
    final AtomicInteger writeCount = new AtomicInteger();
    final Outcome outcome;
    volatile boolean open = true;

    GatedLine(Outcome outcome) {
      this.outcome = outcome;
    }

    void awaitWrite() throws InterruptedException {
      assertTrue(entered.await(5, TimeUnit.SECONDS), "the writer did not reach the driver");
    }

    @Override
    public int read(byte[] buffer, int length) {
      return -1;
    }

    @Override
    public int write(byte[] data, int length) {
      writeCount.incrementAndGet();
      entered.countDown();
      try {
        gate.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return -1;
      }
      return switch (outcome) {
        case SUCCESS -> length;
        case SHORT -> length - 1;
        case ERROR -> -1;
        case THROW -> throw new IllegalStateException("driver failure");
      };
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }
  }
}
