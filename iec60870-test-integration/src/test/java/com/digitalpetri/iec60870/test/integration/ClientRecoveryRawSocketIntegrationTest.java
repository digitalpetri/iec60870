package com.digitalpetri.iec60870.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.ConnectionState;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.ReconnectPolicy;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Drives a stock {@link TcpIec104Client} against a raw-socket outstation that records and answers
 * U-frames by hand, to check that the client re-arms its session on every connection it opens and
 * recovers from each kind of connection loss.
 */
class ClientRecoveryRawSocketIntegrationTest {

  private static final int STARTDT_ACT = 0x07;
  private static final int STARTDT_CON = 0x0B;
  private static final int TESTFR_ACT = 0x43;
  private static final int TESTFR_CON = 0x83;

  private static final ReconnectPolicy FAST_RECONNECT =
      ReconnectPolicy.exponentialBackoff(Duration.ofMillis(100), Duration.ofSeconds(1));

  /** t0 1 s, t1 1 s, t2 0.5 s, t3 1 s. */
  private static final ApciSettings SHORT_TIMERS =
      new ApciSettings(
          UShort.valueOf(12),
          UShort.valueOf(8),
          Duration.ofSeconds(1),
          Duration.ofSeconds(1),
          Duration.ofMillis(500),
          Duration.ofSeconds(1));

  @Test
  void peerFinIsFollowedByARedialThatRearmsTheSession() throws Exception {
    peerDropIsFollowedByARedialThatRearmsTheSession(false);
  }

  @Test
  void peerRstIsFollowedByARedialThatRearmsTheSession() throws Exception {
    peerDropIsFollowedByARedialThatRearmsTheSession(true);
  }

  private void peerDropIsFollowedByARedialThatRearmsTheSession(boolean reset) throws Exception {
    try (var outstation = new RawOutstation()) {
      EventCollector events = new EventCollector();
      Iec60870Client client =
          TcpIec104Client.builder()
              .host("127.0.0.1")
              .port(outstation.port())
              .reconnectPolicy(FAST_RECONNECT)
              .build();
      client.events().subscribe(events);
      try {
        var connecting = client.connectAsync().toCompletableFuture();
        Socket first = outstation.accept();
        confirmStartDt(first);
        connecting.get(5, TimeUnit.SECONDS);

        if (reset) {
          first.setSoLinger(true, 0);
        }
        first.close();

        // The client redials on its own, runs STARTDT, and answers TESTFR on the new connection.
        try (Socket second = outstation.accept()) {
          confirmStartDt(second);
          RawOutstation.write(second, TESTFR_ACT);
          assertEquals(TESTFR_CON, RawOutstation.readUFrame(second, TESTFR_CON));
        }

        Await.until(
            "a second DataTransferStarted",
            () -> count(events, ClientEvent.DataTransferStarted.class) == 2);
        ClientEvent.ConnectionClosed closed = first(events, ClientEvent.ConnectionClosed.class);
        assertNotNull(closed.cause(), "a peer close is reported with a cause");
        if (!reset) {
          assertInstanceOf(ConnectionClosedException.class, closed.cause());
        }
        assertEquals(2, count(events, ClientEvent.ConnectionOpened.class));
        assertTrue(
            indexOf(events, ClientEvent.DataTransferStopped.class)
                < indexOf(events, ClientEvent.ConnectionClosed.class));
      } finally {
        client.close();
      }
    }
  }

  @Test
  void unconfirmedStartDtThenConnectSucceedsWithThePrivateEventLoopGroup() throws Exception {
    unconfirmedStartDtThenConnectSucceeds(null);
  }

  @Test
  void unconfirmedStartDtThenConnectSucceedsWithACallerSuppliedEventLoopGroup() throws Exception {
    EventLoopGroup group = new NioEventLoopGroup(1);
    try {
      unconfirmedStartDtThenConnectSucceeds(group);
    } finally {
      group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    }
  }

  private void unconfirmedStartDtThenConnectSucceeds(@Nullable EventLoopGroup group)
      throws Exception {
    try (var outstation = new RawOutstation()) {
      TcpIec104Client.Builder builder =
          TcpIec104Client.builder()
              .host("127.0.0.1")
              .port(outstation.port())
              .apci(SHORT_TIMERS)
              .reconnectPolicy(ReconnectPolicy.disabled());
      if (group != null) {
        builder.eventLoopGroup(group);
      }
      Iec60870Client client = builder.build();
      try {
        // The outstation is still booting: it accepts but never confirms STARTDT. The attempt is
        // bounded by t0 + t1.
        var first = client.connectAsync().toCompletableFuture();
        Socket booting = outstation.accept();
        RawOutstation.readUFrame(booting, STARTDT_ACT);
        var failure = assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS));
        assertInstanceOf(ProtocolTimeoutException.class, failure.getCause());
        // t3 runs from the connection's opening, and the STARTDT act sent then doesn't restart it,
        // so
        // with t3 equal to t1 a TESTFR act can precede the close. Read up to the end of the stream.
        booting.setSoTimeout(3_000);
        InputStream in = booting.getInputStream();
        while (in.read() != -1) {
          // Discard whatever the client sent before closing.
        }
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState());

        // Long enough for a private event loop group that was shut down to have terminated. With
        // reconnect disabled, nothing dials in the meantime.
        assertThrows(SocketTimeoutException.class, () -> outstation.accept(3_000));

        // The outstation is up now: connect() opens a new connection and runs STARTDT.
        var second = client.connectAsync().toCompletableFuture();
        Socket ready = outstation.accept();
        confirmStartDt(ready);
        second.get(5, TimeUnit.SECONDS);
        assertTrue(client.isConnected());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void t1ExpiryOnASilentLinkRecoversPerPolicy() throws Exception {
    try (var outstation = new RawOutstation()) {
      EventCollector events = new EventCollector();
      Iec60870Client client =
          TcpIec104Client.builder()
              .host("127.0.0.1")
              .port(outstation.port())
              .apci(SHORT_TIMERS)
              .reconnectPolicy(FAST_RECONNECT)
              .build();
      client.events().subscribe(events);
      try {
        var connecting = client.connectAsync().toCompletableFuture();
        Socket halfOpen = outstation.accept();
        confirmStartDt(halfOpen);
        connecting.get(5, TimeUnit.SECONDS);

        // The link goes silent: the client's t3 TESTFR act is never confirmed, so t1 expires.
        RawOutstation.readUFrame(halfOpen, TESTFR_ACT);

        try (Socket recovered = outstation.accept()) {
          confirmStartDt(recovered);
        }
        Await.until(
            "ConnectionClosed with the t1 cause",
            () ->
                events.hasMatch(
                    ClientEvent.ConnectionClosed.class,
                    c -> c.cause() instanceof ProtocolTimeoutException));
        Await.until(
            "a second DataTransferStarted",
            () -> count(events, ClientEvent.DataTransferStarted.class) == 2);
        halfOpen.close();
      } finally {
        client.close();
      }
    }
  }

  @Test
  void connectAfterCloseFailsFast() throws Exception {
    try (var outstation = new RawOutstation()) {
      Iec60870Client client =
          TcpIec104Client.builder().host("127.0.0.1").port(outstation.port()).build();
      var connecting = client.connectAsync().toCompletableFuture();
      confirmStartDt(outstation.accept());
      connecting.get(5, TimeUnit.SECONDS);

      client.close();

      var failure =
          assertThrows(
              ExecutionException.class,
              () -> client.connectAsync().toCompletableFuture().get(1, TimeUnit.SECONDS));
      assertInstanceOf(ConnectionClosedException.class, failure.getCause());
      assertFalse(client.isConnected());
    }
  }

  private static void confirmStartDt(Socket socket) throws IOException {
    RawOutstation.readUFrame(socket, STARTDT_ACT);
    RawOutstation.write(socket, STARTDT_CON);
  }

  private static int count(EventCollector events, Class<? extends ClientEvent> type) {
    return (int) events.events().stream().filter(type::isInstance).count();
  }

  private static int indexOf(EventCollector events, Class<? extends ClientEvent> type) {
    var all = events.events();
    for (int i = 0; i < all.size(); i++) {
      if (type.isInstance(all.get(i))) {
        return i;
      }
    }
    return Integer.MAX_VALUE;
  }

  private static <T extends ClientEvent> T first(EventCollector events, Class<T> type) {
    return events.events().stream()
        .filter(type::isInstance)
        .map(type::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " recorded"));
  }

  /** A loopback listener that exchanges raw IEC 104 frames. */
  private static final class RawOutstation implements AutoCloseable {

    private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());

    private RawOutstation() throws IOException {}

    int port() {
      return server.getLocalPort();
    }

    Socket accept() throws IOException {
      return accept(10_000);
    }

    Socket accept(int timeoutMillis) throws IOException {
      server.setSoTimeout(timeoutMillis);
      Socket socket = server.accept();
      socket.setSoTimeout(10_000);
      return socket;
    }

    /** Reads frames until a U-frame with the given first control octet arrives. */
    static int readUFrame(Socket socket, int control) throws IOException {
      var in = new DataInputStream(socket.getInputStream());
      while (true) {
        int start = in.readUnsignedByte();
        if (start != 0x68) {
          throw new IOException("bad start octet: " + start);
        }
        byte[] body = new byte[in.readUnsignedByte()];
        in.readFully(body);
        if ((body[0] & 0xFF) == control) {
          return control;
        }
      }
    }

    /** Writes a U-frame with the given first control octet. */
    static void write(Socket socket, int control) throws IOException {
      socket.getOutputStream().write(new byte[] {0x68, 4, (byte) control, 0, 0, 0});
      socket.getOutputStream().flush();
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }
}
