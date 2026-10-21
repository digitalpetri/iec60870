/**
 * Opt-in diagnostics for IEC 60870-5 connections: a listener SPI and a per-connection logging
 * context.
 *
 * <p>{@link com.digitalpetri.iec60870.diagnostics.ConnectionDiagnostics} is the protocol-neutral
 * part of the listener. The transports report connect attempts, established connections, and failed
 * attempts through it; the client and server facades report closed connections, scheduled
 * reconnects, inbound pauses, and discarded events or ASDUs. Each link layer extends it with its
 * own frame-level callbacks, for example {@code ApciDiagnostics} in {@code iec60870-cs104}. Every
 * method has an empty default, so a listener overrides only what it needs and the default {@link
 * com.digitalpetri.iec60870.diagnostics.ConnectionDiagnostics#NONE} costs a call to an empty
 * method.
 *
 * <p><b>Threading.</b> Callbacks run on the thread that drives the event: a Netty I/O thread for
 * inbound frames and the connect lifecycle, the scheduler thread for timers, or the thread that
 * caused an outbound write. Some run while a session or facade lock is held. A listener must return
 * quickly, must not block, and must not call back into the client, server, or session. The library
 * catches and logs an exception thrown by a listener, so a faulty listener cannot change protocol
 * state or close a connection.
 *
 * <p><b>Logging context.</b> {@link com.digitalpetri.iec60870.diagnostics.LoggingContext} carries
 * the SLF4J MDC entries that identify a connection. Library components that log on behalf of a
 * connection set the entries around each log call, only when the level is enabled, and restore the
 * previous MDC values afterward so nothing leaks onto shared I/O threads. Applications configure
 * the entries as a plain {@code Map<String, String>} on the builders and configuration records.
 */
@NullMarked
package com.digitalpetri.iec60870.diagnostics;

import org.jspecify.annotations.NullMarked;
