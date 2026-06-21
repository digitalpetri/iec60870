package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.address.PointAddress;
import java.util.Objects;

/**
 * A received read command (C_RD_NA_1) requesting the current value of a single point.
 *
 * <p>Passed to {@link ServerHandler#onRead(ServerContext, ReadRequest)}. The handler answers with a
 * {@link ReadResponse}, usually {@link ServerContext#defaultRead(ReadRequest)}, which reports the
 * point's current value from the station image.
 *
 * @param point the fully qualified address of the point to read.
 */
public record ReadRequest(PointAddress point) {

  /**
   * Validates the components of the request.
   *
   * @param point the fully qualified address of the point to read.
   * @throws NullPointerException if {@code point} is null.
   */
  public ReadRequest {
    Objects.requireNonNull(point, "point");
  }
}
