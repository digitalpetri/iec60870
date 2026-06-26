/**
 * Runnable, documentation-first examples for the IEC 60870 library: IEC 60870-5-104 over TCP/TLS,
 * plus IEC 60870-5-101 (FT1.2) over a serial line or over TCP.
 *
 * <p>Each class in this package has a {@code public static void main} and demonstrates one slice of
 * the API:
 *
 * <ul>
 *   <li>{@link com.digitalpetri.iec60870.examples.ServerExample} — a controlled station that hosts
 *       a few points, accepts a command, and spontaneously publishes a measured value.
 *   <li>{@link com.digitalpetri.iec60870.examples.ClientExample} — a controlling station that
 *       connects, interrogates, sends a command, synchronizes the clock, and prints the events it
 *       receives.
 *   <li>{@link com.digitalpetri.iec60870.examples.RawAsduExample} — the protocol layer without the
 *       client/server facade: building an {@link com.digitalpetri.iec60870.asdu.Asdu} by hand,
 *       encoding it to bytes, and decoding it back.
 *   <li>{@link com.digitalpetri.iec60870.examples.TlsExample} — the client/server pair secured with
 *       TLS, configured from a keystore supplied through system properties.
 *   <li>{@link com.digitalpetri.iec60870.examples.SerialServerExample} — the controlled-station
 *       example over an IEC 60870-5-101 FT1.2 serial link instead of 104 over TCP.
 *   <li>{@link com.digitalpetri.iec60870.examples.SerialClientExample} — the controlling-station
 *       example over an IEC 60870-5-101 FT1.2 serial link, with a balanced and an unbalanced-master
 *       configuration.
 *   <li>{@link com.digitalpetri.iec60870.examples.Tcp101Example} — the IEC 60870-5-101 FT1.2 link
 *       layer carried over TCP, running a server and client in-process on a loopback port.
 * </ul>
 *
 * <p>The {@link com.digitalpetri.iec60870.examples.ServerExample} and {@link
 * com.digitalpetri.iec60870.examples.ClientExample} bodies are factored into {@code run(...)}
 * methods that take an explicit port, so the integration test can drive the same logic on an
 * ephemeral loopback port while the {@code main} methods keep using the standard port 2404.
 *
 * <p>See {@code README.md} in this module for how to run each example.
 */
@NullMarked
package com.digitalpetri.iec60870.examples;

import org.jspecify.annotations.NullMarked;
