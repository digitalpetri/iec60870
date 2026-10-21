package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.diagnostics.ConnectionDiagnostics;
import com.digitalpetri.iec60870.diagnostics.Direction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ApciDiagnosticsTest {

  @Test
  void ofNoneIsNoneAndWrappingIsIdempotent() {
    assertSame(ApciDiagnostics.NONE, ApciDiagnostics.of(ApciDiagnostics.NONE));
    ApciDiagnostics guarded = ApciDiagnostics.of(new RecordingDiagnostics());
    assertSame(guarded, ApciDiagnostics.of(guarded));
  }

  @Test
  void fanOutReachesEveryListenerPastOneThatThrows() {
    var first = new RecordingDiagnostics();
    var second = new RecordingDiagnostics();
    ApciDiagnostics combined = ApciDiagnostics.of(first, new ThrowingDiagnostics(), second);

    combined.onTimerExpired(ApciTimer.T3);
    combined.onApduSent(new ControlField.TypeS(1), 6);

    assertEquals(List.of("timer T3", "sent S(N(R)=1) 6"), first.log);
    assertEquals(first.log, second.log);
  }

  @Test
  void frameTraceReachesOnlyListenersThatTrace() {
    var tracing = new RecordingDiagnostics();
    tracing.trace = true;
    var silent = new RecordingDiagnostics();
    ApciDiagnostics combined = ApciDiagnostics.of(silent, tracing, new ThrowingDiagnostics());
    ByteBuf frame = Unpooled.wrappedBuffer(new byte[] {0x68, 0x04, 0x43, 0, 0, 0}).asReadOnly();

    assertTrue(combined.traceFrames());
    combined.onFrame(Direction.OUT, frame);

    assertEquals(List.of("frame OUT 6"), tracing.log);
    assertEquals(List.of(), silent.log);
    assertEquals(0, frame.readerIndex(), "each listener reads its own view");
    assertFalse(ApciDiagnostics.of(silent).traceFrames());
  }

  @Test
  void fromAdaptsAConnectionListener() {
    List<String> seen = new ArrayList<>();
    ConnectionDiagnostics listener =
        new ConnectionDiagnostics() {
          @Override
          public void onConnectionClosed(@Nullable Throwable cause) {
            seen.add("closed");
          }
        };

    ApciDiagnostics adapted = ApciDiagnostics.from(listener);
    adapted.onConnectionClosed(null);
    adapted.onTimerExpired(ApciTimer.T1);

    assertEquals(List.of("closed"), seen);
    assertSame(ApciDiagnostics.NONE, ApciDiagnostics.from(ConnectionDiagnostics.NONE));
    var apci = new RecordingDiagnostics();
    assertSame(apci, ApciDiagnostics.from(apci));
    adapted.onConnected(
        InetSocketAddress.createUnresolved("a", 1), InetSocketAddress.createUnresolved("b", 2));
  }
}
