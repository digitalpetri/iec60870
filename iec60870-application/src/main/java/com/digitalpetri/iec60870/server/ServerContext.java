package com.digitalpetri.iec60870.server;

import com.digitalpetri.iec60870.asdu.Asdu;
import java.net.SocketAddress;
import java.util.Optional;

/**
 * The per-request context handed to a {@link ServerHandler} callback.
 *
 * <p>A context is bound to the connection the request arrived on and, when the request's common
 * address matches a configured station, to that {@link Station}. It exposes the connection's {@link
 * #remoteAddress() remote address}, default-answer helpers that build the standard response from
 * the station's value image, and a raw {@link #send(Asdu)} escape hatch for emitting additional
 * ASDUs on the same connection (for example spontaneous data while handling a command).
 *
 * <p>A context instance is valid only for the duration of the handler callback it was passed to; do
 * not retain it. Handler callbacks for a single connection are serialized, so a handler never
 * receives two contexts for the same connection concurrently.
 */
public interface ServerContext {

  /**
   * Returns the remote socket address of the connection the request arrived on.
   *
   * @return the peer's remote address.
   */
  SocketAddress remoteAddress();

  /**
   * Returns the station addressed by the request, if its common address matches a configured
   * station.
   *
   * @return the matched station, or an empty {@link Optional} if no station has the request's
   *     common address.
   */
  Optional<Station> station();

  /**
   * Builds the standard answer to an interrogation from the matched station's value image.
   *
   * <p>Reports every {@linkplain com.digitalpetri.iec60870.point.PointCapability#REPORTED reported}
   * point selected by the request's qualifier of interrogation. If the request's common address
   * matches no station, the returned response declines the interrogation with {@link
   * com.digitalpetri.iec60870.asdu.Cause#UNKNOWN_COMMON_ADDRESS}.
   *
   * @param request the interrogation request.
   * @return the standard interrogation response.
   * @throws NullPointerException if {@code request} is null.
   */
  InterrogationResponse defaultInterrogation(InterrogationRequest request);

  /**
   * Builds the standard answer to a read from the matched station's value image.
   *
   * <p>Reports the current value of the addressed point. If the request's common address matches no
   * station the response declines with {@link
   * com.digitalpetri.iec60870.asdu.Cause#UNKNOWN_COMMON_ADDRESS}; if the station hosts no point at
   * the read address it declines with {@link
   * com.digitalpetri.iec60870.asdu.Cause#UNKNOWN_INFORMATION_OBJECT_ADDRESS}.
   *
   * @param request the read request.
   * @return the standard read response.
   * @throws NullPointerException if {@code request} is null.
   */
  ReadResponse defaultRead(ReadRequest request);

  /**
   * Sends an arbitrary ASDU on this connection.
   *
   * <p>This is an escape hatch for behavior the typed responses do not cover; the ASDU is queued on
   * the connection's APCI session and transmitted subject to the data-transfer state and
   * flow-control window. The send is fire-and-forget.
   *
   * @param asdu the ASDU to send.
   * @throws NullPointerException if {@code asdu} is null.
   */
  void send(Asdu asdu);
}
