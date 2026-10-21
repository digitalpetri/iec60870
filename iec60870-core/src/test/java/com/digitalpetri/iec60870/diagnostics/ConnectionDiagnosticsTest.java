package com.digitalpetri.iec60870.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ConnectionDiagnosticsTest {

  private static final SocketAddress REMOTE = InetSocketAddress.createUnresolved("rtu", 2404);

  @Test
  void ofNoneIsNoneAndWrappingIsIdempotent() {
    assertSame(ConnectionDiagnostics.NONE, ConnectionDiagnostics.of(ConnectionDiagnostics.NONE));
    ConnectionDiagnostics guarded = ConnectionDiagnostics.of(new ConnectionDiagnostics() {});
    assertSame(guarded, ConnectionDiagnostics.of(guarded));
  }

  @Test
  void aThrowingListenerDoesNotStopTheOthersOrTheCaller() {
    List<String> seen = new ArrayList<>();
    ConnectionDiagnostics throwing =
        new ConnectionDiagnostics() {
          @Override
          public void onConnectAttempt(SocketAddress remote) {
            throw new IllegalStateException("attempt");
          }

          @Override
          public void onConnectionClosed(@Nullable Throwable cause) {
            throw new IllegalStateException("closed");
          }
        };
    ConnectionDiagnostics recording =
        new ConnectionDiagnostics() {
          @Override
          public void onConnectAttempt(SocketAddress remote) {
            seen.add("attempt");
          }

          @Override
          public void onConnectionClosed(@Nullable Throwable cause) {
            seen.add("closed");
          }
        };

    ConnectionDiagnostics combined = ConnectionDiagnostics.of(throwing, recording);
    combined.onConnectAttempt(REMOTE);
    combined.onConnectionClosed(null);

    assertEquals(List.of("attempt", "closed"), seen);
  }
}
