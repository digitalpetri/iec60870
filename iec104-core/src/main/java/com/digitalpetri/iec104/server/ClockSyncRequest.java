package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import java.util.Objects;

/**
 * A received clock synchronization command (C_CS_NA_1) supplying a new station clock value.
 *
 * <p>Passed to {@link ServerHandler#onClockSync(ServerContext, ClockSyncRequest)}. The handler
 * answers with a {@link ClockSyncDecision}; the default decision accepts and the server replies
 * with a positive activation confirmation.
 *
 * @param station the common address of the station whose clock is being set.
 * @param time the CP56Time2a clock value to synchronize to.
 */
public record ClockSyncRequest(CommonAddress station, Cp56Time2a time) {

  /**
   * Validates the components.
   *
   * @param station the common address of the station whose clock is being set.
   * @param time the CP56Time2a clock value to synchronize to.
   * @throws NullPointerException if {@code station} or {@code time} is null.
   */
  public ClockSyncRequest {
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(time, "time");
  }
}
