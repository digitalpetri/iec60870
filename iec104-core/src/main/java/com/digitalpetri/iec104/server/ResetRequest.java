package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.asdu.element.QualifierOfResetProcess;
import java.util.Objects;

/**
 * A received reset process command (C_RP_NA_1) directed at a station.
 *
 * <p>Passed to {@link ServerHandler#onReset(ServerContext, ResetRequest)}. The {@link #qualifier()}
 * selects a general process reset or a reset of the time-tagged event buffer. The handler answers
 * with a {@link ResetDecision}; the default decision accepts and the server replies with a positive
 * activation confirmation.
 *
 * @param station the common address of the station being reset.
 * @param qualifier the qualifier of reset process (QRP) selecting the reset action.
 */
public record ResetRequest(CommonAddress station, QualifierOfResetProcess qualifier) {

  /**
   * Validates the components of the request.
   *
   * @param station the common address of the station being reset.
   * @param qualifier the qualifier of reset process (QRP) selecting the reset action.
   * @throws NullPointerException if {@code station} or {@code qualifier} is null.
   */
  public ResetRequest {
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(qualifier, "qualifier");
  }
}
