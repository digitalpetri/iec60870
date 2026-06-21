package com.digitalpetri.iec104.examples;

import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.client.ClientEvent;
import com.digitalpetri.iec104.client.CommandResult;
import com.digitalpetri.iec104.client.Iec104Client;
import com.digitalpetri.iec104.client.InterrogationResult;
import com.digitalpetri.iec104.transport.tcp.TcpIec104Client;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Flow;

/**
 * A controlling station (client) that connects to a controlled station on loopback, prints the
 * events it receives, and exercises the main request operations.
 *
 * <p>The {@link #run(String, int, Duration)} method performs, in order: connect (which also starts
 * data transfer because {@code startDataTransferOnConnect} defaults to {@code true}), a general
 * interrogation of common address {@code 1}, a single command (direct execute) on the commandable
 * point, a clock synchronization, then it lingers for the supplied duration so spontaneous updates
 * from the server arrive on the event stream before the client closes.
 *
 * <p>A {@link Flow.Subscriber} subscribed to {@link Iec104Client#events()} prints {@link
 * ClientEvent.PointUpdated} and {@link ClientEvent.AsduReceived} events as they arrive; events are
 * delivered serially on the client's callback executor.
 */
public final class ClientExample {

  /** Common address of the controlled station to interrogate. */
  static final CommonAddress STATION = CommonAddress.of(1);

  /** Commandable single point on the controlled station. */
  static final PointAddress SWITCH = PointAddress.of(1, 300);

  private ClientExample() {}

  /**
   * Connects to the controlled station, runs the request operations, lingers for spontaneous
   * updates, then closes the client.
   *
   * @param host the controlled station host.
   * @param port the controlled station port.
   * @param linger how long to wait for spontaneous updates after the request operations complete.
   */
  public static void run(String host, int port, Duration linger) {
    try (Iec104Client client =
        TcpIec104Client.builder().host(host).port(port).startDataTransferOnConnect(true).build()) {

      // Subscribe before connecting so no early events are missed.
      client.events().subscribe(new PrintingSubscriber());

      System.out.println("[client] connecting to " + host + ":" + port);
      client.connect();
      System.out.println("[client] connected; data transfer started");

      InterrogationResult snapshot = client.interrogate(STATION);
      System.out.println(
          "[client] interrogation reported " + snapshot.pointValues().size() + " points");
      for (InterrogationResult.PointEntry entry : snapshot.pointValues()) {
        System.out.println("[client]   " + entry.address() + " = " + entry.value().value());
      }

      CommandResult command = client.commands().single(SWITCH, true);
      System.out.println("[client] command on " + SWITCH + " positive=" + command.positive());

      client.synchronizeClock(STATION, Instant.now());
      System.out.println("[client] clock synchronized");

      System.out.println("[client] waiting " + linger.toMillis() + " ms for spontaneous updates");
      sleep(linger);
    }
    System.out.println("[client] closed");
  }

  /**
   * Connects to {@code 127.0.0.1:2404} and runs the example, lingering three seconds for
   * spontaneous updates.
   *
   * @param args ignored.
   */
  public static void main(String[] args) {
    run("127.0.0.1", 2404, Duration.ofSeconds(3));
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(Math.max(0, duration.toMillis()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * A subscriber that requests every event and prints the point updates and received ASDUs it sees.
   */
  private static final class PrintingSubscriber implements Flow.Subscriber<ClientEvent> {

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ClientEvent event) {
      if (event instanceof ClientEvent.PointUpdated updated) {
        System.out.println(
            "[client] point updated "
                + updated.address()
                + " = "
                + updated.value().value()
                + " ("
                + updated.cause()
                + ")");
      } else if (event instanceof ClientEvent.AsduReceived received) {
        System.out.println("[client] asdu received " + received.asdu().type());
      } else if (event instanceof ClientEvent.ConnectionClosed closed) {
        System.out.println("[client] connection closed: " + closed.causeOptional());
      }
      // Other lifecycle events are not printed in this example.
    }

    @Override
    public void onError(Throwable throwable) {
      System.out.println("[client] event stream error: " + throwable);
    }

    @Override
    public void onComplete() {
      System.out.println("[client] event stream complete");
    }
  }
}
