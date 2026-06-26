package com.digitalpetri.iec60870.transport.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the {@link SerialServerTransport#bind()} failure path.
 *
 * <p>{@code bind()} hands the new connection to the registered handler — which synchronously builds
 * and registers a server-role session (a {@link TransportListener}) bound to that connection —
 * <em>before</em> opening the port. If {@code open()} then fails, the already-registered session
 * must be torn down: the connection is closed (delivering {@code onConnectionLost} so any armed
 * link-state timer is cancelled) and {@code this.connection} is cleared so it is never left
 * referencing a half-constructed, dead connection. Otherwise the orphaned session and its timer
 * would leak on the shared scheduler.
 */
class SerialServerTransportBindFailureTest {

  @Test
  void failedOpenTearsDownRegisteredSessionAndClearsConnection() throws Exception {
    // A port name that cannot be opened: bind() proceeds to open() and fails there.
    SerialPortConfig config =
        SerialPortConfig.builder("/dev/iec60870-finding13-nonexistent-port").build();

    SerialServerTransport transport = new SerialServerTransport(config);

    // Stand in for the server-role session that Cs101Binding.bindServer would register; if it is
    // never told the connection was lost, its armed timer leaks on the shared scheduler.
    RecordingListener session = new RecordingListener();
    transport.setConnectionHandler(connection -> connection.setListener(session));

    CompletableFuture<Void> bind = transport.bind().toCompletableFuture();

    assertTrue(bind.isCompletedExceptionally(), "bind() must fail when the port cannot be opened");
    assertEquals(
        1,
        session.lossCount(),
        "the registered session must be told the connection was lost exactly once");
    assertNull(
        connectionField(transport),
        "this.connection must be cleared after a failed bind so no dead connection is referenced");
  }

  private static @Nullable Object connectionField(SerialServerTransport transport)
      throws ReflectiveOperationException {
    Field field = SerialServerTransport.class.getDeclaredField("connection");
    field.setAccessible(true);
    return field.get(transport);
  }

  /** Records connection-loss notifications, standing in for a registered server-role session. */
  private static final class RecordingListener implements TransportListener {

    private final AtomicInteger lossCount = new AtomicInteger();

    @Override
    public void onFrame(ByteBuf frame) {
      // not exercised in this test
    }

    @Override
    public void onConnectionLost(@Nullable Throwable cause) {
      lossCount.incrementAndGet();
    }

    int lossCount() {
      return lossCount.get();
    }
  }
}
