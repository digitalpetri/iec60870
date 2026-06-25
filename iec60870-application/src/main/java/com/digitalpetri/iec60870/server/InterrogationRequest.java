package com.digitalpetri.iec60870.server;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import java.util.Objects;

/**
 * A received interrogation command (C_IC_NA_1) directed at a station.
 *
 * <p>Passed to {@link ServerHandler#onInterrogation(ServerContext, InterrogationRequest)}. The
 * {@link #qualifier()} selects a global station interrogation or a specific group; the handler
 * answers with an {@link InterrogationResponse}, usually {@link
 * ServerContext#defaultInterrogation(InterrogationRequest)}.
 *
 * @param station the common address of the interrogated station.
 * @param qualifier the qualifier of interrogation (QOI) selecting the station or group.
 */
public record InterrogationRequest(CommonAddress station, QualifierOfInterrogation qualifier) {

  /**
   * Validates the components of the request.
   *
   * @param station the common address of the interrogated station.
   * @param qualifier the qualifier of interrogation (QOI) selecting the station or group.
   * @throws NullPointerException if {@code station} or {@code qualifier} is null.
   */
  public InterrogationRequest {
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(qualifier, "qualifier");
  }
}
