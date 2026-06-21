/**
 * Runnable, documentation-first examples for the IEC 60870-5-104 library.
 *
 * <p>Each class in this package has a {@code public static void main} and demonstrates one slice of
 * the API:
 *
 * <ul>
 *   <li>{@link com.digitalpetri.iec104.examples.ServerExample} — a controlled station that hosts a
 *       few points, accepts a command, and spontaneously publishes a measured value.
 *   <li>{@link com.digitalpetri.iec104.examples.ClientExample} — a controlling station that
 *       connects, interrogates, sends a command, synchronizes the clock, and prints the events it
 *       receives.
 *   <li>{@link com.digitalpetri.iec104.examples.RawAsduExample} — the protocol layer without the
 *       client/server facade: building an {@link com.digitalpetri.iec104.asdu.Asdu} by hand,
 *       encoding it to bytes, and decoding it back.
 *   <li>{@link com.digitalpetri.iec104.examples.TlsExample} — the client/server pair secured with
 *       TLS, configured from a keystore supplied through system properties.
 * </ul>
 *
 * <p>The {@link com.digitalpetri.iec104.examples.ServerExample} and {@link
 * com.digitalpetri.iec104.examples.ClientExample} bodies are factored into {@code run(...)} methods
 * that take an explicit port, so the integration test can drive the same logic on an ephemeral
 * loopback port while the {@code main} methods keep using the standard port 2404.
 *
 * <p>See {@code README.md} in this module for how to run each example.
 */
package com.digitalpetri.iec104.examples;
