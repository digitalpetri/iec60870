package com.digitalpetri.iec60870.client;

/**
 * The state of an {@link Iec60870Client}'s managed connection, as reported by {@link
 * Iec60870Client#connectionState()}.
 *
 * <p>A new client starts {@link #DISCONNECTED}. {@link Iec60870Client#connect()} moves it to {@link
 * #CONNECTING} and, once the session is armed on a live connection, to {@link #CONNECTED}. After an
 * unplanned loss or a failed attempt the client waits in {@link #RECONNECT_WAIT} and then tries
 * again in {@code CONNECTING}, unless its {@link ReconnectPolicy} is disabled, in which case it
 * returns to {@code DISCONNECTED}. {@link Iec60870Client#disconnect()} returns it to {@code
 * DISCONNECTED}, and {@link Iec60870Client#close()} ends it in {@link #CLOSED}.
 */
public enum ConnectionState {

  /** No connection is wanted: the client was never connected, or was disconnected. */
  DISCONNECTED,

  /**
   * A connection attempt is in progress: dialing, the TLS handshake, or session initialization
   * including {@code STARTDT} when configured.
   */
  CONNECTING,

  /** The session is armed on a live connection. */
  CONNECTED,

  /** The connection was lost or an attempt failed, and the next attempt is scheduled. */
  RECONNECT_WAIT,

  /** The client was closed and cannot be connected again. */
  CLOSED
}
