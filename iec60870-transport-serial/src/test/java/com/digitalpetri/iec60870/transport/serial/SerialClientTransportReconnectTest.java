package com.digitalpetri.iec60870.transport.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Regression test for finding #5's defensive guard in {@link SerialClientTransport#connect()}: a
 * second {@code connect()} (for example from an application's connection-loss handler) without an
 * intervening {@code disconnect()} must close any existing channel before replacing it, so the old
 * channel cannot leak — or, by keeping the OS port open, block reopening — the serial port.
 */
class SerialClientTransportReconnectTest {

  @Test
  void connectClosesExistingChannelBeforeReplacing() throws Exception {
    AtomicInteger staleLoss = new AtomicInteger();
    Ft12SerialChannel stale =
        new Ft12SerialChannel(frame -> {}, cause -> staleLoss.incrementAndGet());

    // A port name that cannot be opened: the fresh open() fails, but the defensive close of the
    // pre-existing channel happens first, independent of the open outcome.
    SerialClientTransport transport =
        new SerialClientTransport(
            SerialPortConfig.builder("/dev/iec60870-finding5-nonexistent-port").build());
    setChannel(transport, stale);

    transport.connect();

    assertEquals(1, staleLoss.get(), "connect() must close the pre-existing channel exactly once");
  }

  private static void setChannel(
      SerialClientTransport transport, @Nullable Ft12SerialChannel channel)
      throws ReflectiveOperationException {
    Field field = SerialClientTransport.class.getDeclaredField("channel");
    field.setAccessible(true);
    field.set(transport, channel);
  }
}
