package com.digitalpetri.iec60870.test.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.UnsupportedAsduTypeException;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.joou.UShort;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end reproduction from the undecodable-ASDU issue: a raw-socket outstation answers
 * STARTDT and then sends one I-frame, and a stock {@link TcpIec104Client} must acknowledge it on
 * the same connection whether or not its ASDU decodes. Before the fix, an undecodable ASDU closed
 * the connection without an acknowledgement, and an outstation that retransmits unconfirmed
 * I-frames looped forever.
 */
class UndecodableAsduRawSocketIntegrationTest {

  private static final ApciSettings APCI =
      new ApciSettings(
          UShort.valueOf(12),
          UShort.valueOf(8),
          Duration.ofSeconds(30),
          Duration.ofSeconds(15),
          Duration.ofMillis(200),
          Duration.ofSeconds(20));

  @Test
  void validAsduIsAcknowledgedAndDelivered() throws Exception {
    EventCollector events = run("01010300010064000001");

    assertTrue(events.hasMatch(ClientEvent.AsduReceived.class, e -> true));
  }

  @Test
  void privateTypeIdIsAcknowledgedAndReported() throws Exception {
    EventCollector events = run("88010300010064000001");

    ClientEvent.UndecodableAsdu undecodable = onlyUndecodable(events);
    assertArrayEquals(HexFormat.of().parseHex("88010300010064000001"), undecodable.asdu());
    assertInstanceOf(UnsupportedAsduTypeException.class, undecodable.cause());
  }

  @Test
  void privateCauseIsAcknowledgedAndReported() throws Exception {
    EventCollector events = run("01013000010064000001");

    assertInstanceOf(AsduDecodeException.class, onlyUndecodable(events).cause());
  }

  @Test
  void unsetTimeTagIsAcknowledgedAndItsValueDelivered() throws Exception {
    // M_SP_TB_1, SPI=1, CP56Time2a all zero with IV=1.
    EventCollector events = run("1e01030001006400000100008000000000");

    assertTrue(
        events.hasMatch(
            ClientEvent.PointUpdated.class,
            u -> u.asduType() == AsduType.M_SP_TB_1 && Boolean.TRUE.equals(u.value().value())));
    assertFalse(events.hasMatch(ClientEvent.UndecodableAsdu.class, e -> true));
  }

  /**
   * Runs one outstation exchange with {@code asduHex} as the I-frame payload and asserts that the
   * client acknowledged it on the only connection and stayed connected.
   */
  private static EventCollector run(String asduHex) throws Exception {
    byte[] asdu = HexFormat.of().parseHex(asduHex);
    AtomicInteger connections = new AtomicInteger();
    CompletableFuture<Integer> acknowledged = new CompletableFuture<>();

    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread outstation =
          new Thread(() -> outstation(listener, asdu, connections, acknowledged), "outstation");
      outstation.setDaemon(true);
      outstation.start();

      EventCollector events = new EventCollector();
      Iec60870Client client =
          TcpIec104Client.builder()
              .host("127.0.0.1")
              .port(listener.getLocalPort())
              .apci(APCI)
              .build();
      try {
        client.events().subscribe(events);
        client.connect();

        int nr = acknowledged.get(5, TimeUnit.SECONDS);
        assertEquals(1, nr, "the client must acknowledge the I-frame with N(R)=1");
        Await.until(
            "the I-frame to be reported",
            () ->
                events.hasMatch(ClientEvent.AsduReceived.class, e -> true)
                    || events.hasMatch(ClientEvent.UndecodableAsdu.class, e -> true));

        assertTrue(client.isConnected(), "the client must stay connected");
        assertEquals(1, connections.get(), "the client must not reconnect");
        assertFalse(events.hasMatch(ClientEvent.ConnectionClosed.class, e -> true));
        return events;
      } finally {
        client.close();
      }
    }
  }

  private static ClientEvent.UndecodableAsdu onlyUndecodable(EventCollector events) {
    List<ClientEvent.UndecodableAsdu> undecodable =
        events.events().stream()
            .filter(ClientEvent.UndecodableAsdu.class::isInstance)
            .map(ClientEvent.UndecodableAsdu.class::cast)
            .toList();
    assertEquals(1, undecodable.size());
    assertFalse(events.hasMatch(ClientEvent.AsduReceived.class, e -> true));
    return undecodable.get(0);
  }

  /**
   * Answers STARTDT act on the first connection, sends one I-frame with N(S)=0, and completes
   * {@code acknowledged} with the N(R) of the first S- or I-frame that acknowledges it. Keeps the
   * connection open afterward and counts any further connections.
   */
  private static void outstation(
      ServerSocket listener,
      byte[] asdu,
      AtomicInteger connections,
      CompletableFuture<Integer> acknowledged) {
    while (!listener.isClosed()) {
      try (Socket socket = listener.accept()) {
        connections.incrementAndGet();
        socket.setSoTimeout(10_000);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();

        readFrame(in); // STARTDT act
        out.write(new byte[] {0x68, 0x04, 0x0B, 0x00, 0x00, 0x00}); // STARTDT con
        byte[] iFrame = new byte[6 + asdu.length]; // N(S)=0, N(R)=0
        iFrame[0] = 0x68;
        iFrame[1] = (byte) (4 + asdu.length);
        System.arraycopy(asdu, 0, iFrame, 6, asdu.length);
        out.write(iFrame);

        while (true) {
          byte[] frame = readFrame(in);
          int c0 = frame[0] & 0xFF;
          int nr = ((frame[2] & 0xFE) >> 1) | ((frame[3] & 0xFF) << 7);
          boolean numbered = (c0 & 0x01) == 0 || (c0 & 0x03) == 0x01;
          if (numbered && nr >= 1) {
            acknowledged.complete(nr);
          }
        }
      } catch (IOException e) {
        // The client closed the connection or the listener was closed.
      }
    }
  }

  /** Reads one APDU and returns its four control octets followed by any ASDU octets. */
  private static byte[] readFrame(DataInputStream in) throws IOException {
    int start = in.readUnsignedByte();
    if (start != 0x68) {
      throw new IOException("bad start octet: " + start);
    }
    byte[] body = new byte[in.readUnsignedByte()];
    in.readFully(body);
    return body;
  }
}
