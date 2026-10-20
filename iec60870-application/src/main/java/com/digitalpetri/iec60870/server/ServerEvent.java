package com.digitalpetri.iec60870.server;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.asdu.Asdu;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An event published by an {@link Iec60870Server} to subscribers of {@link
 * Iec60870Server#events()}.
 *
 * <p>Events are delivered serially on the server's configured callback executor: a subscriber never
 * observes two events concurrently. The sealed hierarchy lets a subscriber dispatch on the concrete
 * event type with a {@code switch}:
 *
 * <pre>{@code
 * server.events().subscribe(subscriber);
 * // inside Flow.Subscriber#onNext(ServerEvent event):
 * switch (event) {
 *   case ServerEvent.ConnectionAccepted a -> log(a.remoteAddress());
 *   case ServerEvent.CommandReceived c -> audit(c.asdu());
 *   default -> {}
 * }
 * }</pre>
 */
public sealed interface ServerEvent
    permits ServerEvent.ConnectionAccepted,
        ServerEvent.ConnectionClosed,
        ServerEvent.DataTransferStarted,
        ServerEvent.DataTransferStopped,
        ServerEvent.CommandReceived,
        ServerEvent.AsduReceived,
        ServerEvent.UndecodableAsdu {

  /**
   * Returns the remote address of the connection the event relates to.
   *
   * @return the peer's remote address.
   */
  SocketAddress remoteAddress();

  /**
   * Published when a controlling-station connection has been accepted.
   *
   * @param remoteAddress the remote address of the accepted connection.
   */
  record ConnectionAccepted(SocketAddress remoteAddress) implements ServerEvent {

    /**
     * Validates the components of the event.
     *
     * @param remoteAddress the remote address of the accepted connection.
     * @throws NullPointerException if {@code remoteAddress} is null.
     */
    public ConnectionAccepted {
      Objects.requireNonNull(remoteAddress, "remoteAddress");
    }
  }

  /**
   * Published once when a controlling-station connection is closed or lost.
   *
   * @param remoteAddress the remote address of the closed connection.
   * @param cause the failure that closed the connection, or {@code null} for an orderly close.
   */
  record ConnectionClosed(SocketAddress remoteAddress, @Nullable Throwable cause)
      implements ServerEvent {

    /**
     * Validates the components of the event.
     *
     * @param remoteAddress the remote address of the closed connection.
     * @param cause the failure that closed the connection, or {@code null} for an orderly close.
     * @throws NullPointerException if {@code remoteAddress} is null.
     */
    public ConnectionClosed {
      Objects.requireNonNull(remoteAddress, "remoteAddress");
    }

    /**
     * Returns the cause of the close.
     *
     * @return the failure that closed the connection, or an empty {@link Optional} for an orderly
     *     close.
     */
    public Optional<Throwable> causeOptional() {
      return Optional.ofNullable(cause);
    }
  }

  /**
   * Published when a connection's user-data transfer has started (a {@code STARTDT} handshake
   * completed).
   *
   * @param remoteAddress the remote address of the connection.
   */
  record DataTransferStarted(SocketAddress remoteAddress) implements ServerEvent {

    /**
     * Validates the components of the event.
     *
     * @param remoteAddress the remote address of the connection.
     * @throws NullPointerException if {@code remoteAddress} is null.
     */
    public DataTransferStarted {
      Objects.requireNonNull(remoteAddress, "remoteAddress");
    }
  }

  /**
   * Published when a connection's user-data transfer has stopped (a {@code STOPDT} handshake
   * completed).
   *
   * @param remoteAddress the remote address of the connection.
   */
  record DataTransferStopped(SocketAddress remoteAddress) implements ServerEvent {

    /**
     * Validates the components of the event.
     *
     * @param remoteAddress the remote address of the connection.
     * @throws NullPointerException if {@code remoteAddress} is null.
     */
    public DataTransferStopped {
      Objects.requireNonNull(remoteAddress, "remoteAddress");
    }
  }

  /**
   * Published when a control command is received from a controlling station.
   *
   * @param remoteAddress the remote address of the connection the command arrived on.
   * @param asdu the received command ASDU.
   */
  record CommandReceived(SocketAddress remoteAddress, Asdu asdu) implements ServerEvent {

    /**
     * Validates the components of the event.
     *
     * @param remoteAddress the remote address of the connection the command arrived on.
     * @param asdu the received command ASDU.
     * @throws NullPointerException if {@code remoteAddress} or {@code asdu} is null.
     */
    public CommandReceived {
      Objects.requireNonNull(remoteAddress, "remoteAddress");
      Objects.requireNonNull(asdu, "asdu");
    }
  }

  /**
   * Published for every ASDU received from a controlling station, in addition to any more specific
   * event derived from it.
   *
   * @param remoteAddress the remote address of the connection the ASDU arrived on.
   * @param asdu the received ASDU.
   */
  record AsduReceived(SocketAddress remoteAddress, Asdu asdu) implements ServerEvent {

    /**
     * Validates the components of the event.
     *
     * @param remoteAddress the remote address of the connection the ASDU arrived on.
     * @param asdu the received ASDU.
     * @throws NullPointerException if {@code remoteAddress} or {@code asdu} is null.
     */
    public AsduReceived {
      Objects.requireNonNull(remoteAddress, "remoteAddress");
      Objects.requireNonNull(asdu, "asdu");
    }
  }

  /**
   * Published for every ASDU received from a controlling station that the session accepted but
   * could not decode, in place of {@link AsduReceived}.
   *
   * <p>The frame was correctly framed, so the session acknowledged it and the connection stays
   * open. When the type identification is undefined or has no codec, the server answers with the
   * ASDU mirrored back with P/N=1 and cause {@code UNKNOWN_TYPE_ID} (44); when only the cause of
   * transmission is undefined, it answers with cause {@code UNKNOWN_CAUSE} (45). Other decode
   * failures have no defined reply.
   *
   * @param remoteAddress the remote address of the connection the ASDU arrived on.
   * @param asdu the raw ASDU octets, starting at the type identification.
   * @param cause the decode failure, an {@link com.digitalpetri.iec60870.AsduDecodeException} or an
   *     {@link com.digitalpetri.iec60870.UnsupportedAsduTypeException}.
   */
  record UndecodableAsdu(SocketAddress remoteAddress, byte[] asdu, Iec60870Exception cause)
      implements ServerEvent {

    /**
     * Validates the components of the event and copies the octets.
     *
     * @param remoteAddress the remote address of the connection the ASDU arrived on.
     * @param asdu the raw ASDU octets, starting at the type identification.
     * @param cause the decode failure.
     * @throws NullPointerException if any component is null.
     */
    public UndecodableAsdu {
      Objects.requireNonNull(remoteAddress, "remoteAddress");
      asdu = Objects.requireNonNull(asdu, "asdu").clone();
      Objects.requireNonNull(cause, "cause");
    }

    /**
     * Returns a copy of the raw ASDU octets.
     *
     * @return the raw ASDU octets, starting at the type identification.
     */
    @Override
    public byte[] asdu() {
      return asdu.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof UndecodableAsdu other
          && remoteAddress.equals(other.remoteAddress)
          && Arrays.equals(asdu, other.asdu)
          && cause.equals(other.cause);
    }

    @Override
    public int hashCode() {
      return Objects.hash(remoteAddress, Arrays.hashCode(asdu), cause);
    }

    @Override
    public String toString() {
      return "UndecodableAsdu[remoteAddress="
          + remoteAddress
          + ", asdu="
          + HexFormat.of().formatHex(asdu)
          + ", cause="
          + cause
          + "]";
    }
  }
}
