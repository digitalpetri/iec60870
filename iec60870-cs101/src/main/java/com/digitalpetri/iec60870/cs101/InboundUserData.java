package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.session.Session;
import org.jspecify.annotations.Nullable;

/**
 * The application payload of an inbound variable frame: a decoded ASDU, or the octets of one that
 * could not be decoded.
 *
 * <p>The engines run the same link procedure (frame count bit, acknowledgement, polling) for both
 * kinds and hand the payload to {@link #deliverTo(Session.Events)} at the point where they would
 * deliver an ASDU.
 */
sealed interface InboundUserData {

  /**
   * Returns the payload of {@code frame}.
   *
   * @param frame the inbound frame.
   * @return the payload of a variable frame, or {@code null} for a frame that carries no ASDU.
   */
  static @Nullable InboundUserData of(Ft12Frame frame) {
    if (frame instanceof Ft12Frame.Variable variable) {
      return new Decoded(variable.asdu());
    }
    if (frame instanceof Ft12Frame.UndecodableVariable undecodable) {
      return new Undecodable(undecodable.asdu(), undecodable.cause());
    }
    return null;
  }

  /**
   * Delivers the payload to the session's event sink.
   *
   * @param events the event sink.
   */
  void deliverTo(Session.Events events);

  /** A decoded ASDU, delivered through {@link Session.Events#onAsdu(Asdu)}. */
  record Decoded(Asdu asdu) implements InboundUserData {
    @Override
    public void deliverTo(Session.Events events) {
      events.onAsdu(asdu);
    }
  }

  /**
   * Undecodable ASDU octets, delivered through {@link Session.Events#onUndecodableAsdu(byte[],
   * Iec60870Exception)}.
   */
  record Undecodable(byte[] asdu, Iec60870Exception cause) implements InboundUserData {
    @Override
    public void deliverTo(Session.Events events) {
      events.onUndecodableAsdu(asdu.clone(), cause);
    }
  }
}
