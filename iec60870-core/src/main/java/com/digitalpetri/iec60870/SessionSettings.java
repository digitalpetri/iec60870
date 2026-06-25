package com.digitalpetri.iec60870;

/**
 * Marker interface for the protocol-specific settings that configure a session.
 *
 * <p>A session is the protocol-neutral handle that drives the data-transfer lifecycle over an octet
 * transport (104's APCI session today, a future 101 link layer tomorrow). The concrete settings
 * that parameterize a session are protocol-specific — for example {@link ApciSettings} carries the
 * 104 {@code k}/{@code w} window and the {@code t0}-{@code t3} timers — so the client and server
 * configurations hold this neutral handle and the binding that assembles the session downcasts it
 * to the type it understands.
 *
 * <p>This is deliberately <em>not</em> a sealed type: a sealed hierarchy would require its
 * permitted subtypes to live in the same module, but the protocol-specific settings live in their
 * own protocol modules, which depend on the core (not the reverse). An open marker keeps that
 * dependency direction correct.
 */
public interface SessionSettings {}
