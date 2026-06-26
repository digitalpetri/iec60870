/**
 * Shared, core-level test fixtures reused across the module test suites.
 *
 * <p>These fixtures speak only in core types — the {@link
 * com.digitalpetri.iec60870.session.Session} SPI, the octet transport SPI ({@link
 * com.digitalpetri.iec60870.transport.ClientTransport} and friends), and whole-frame {@code
 * ByteBuf}s — so {@code iec60870-cs104}, {@code iec60870-application}, {@code
 * iec60870-transport-tcp}, and {@code iec60870-test-integration} can each consume them at test
 * scope without duplicating the scaffolding. They are deliberately simple hand-written doubles: a
 * deterministic virtual-clock scheduler ({@link
 * com.digitalpetri.iec60870.test.common.ManualScheduler}), a {@code Session.Events} recorder
 * ({@link com.digitalpetri.iec60870.test.common.RecordingEvents}), frame-capturing transports
 * ({@link com.digitalpetri.iec60870.test.common.RecordingClientTransport}, {@link
 * com.digitalpetri.iec60870.test.common.RecordingServerConnection}), and a Netty leak-detection
 * JUnit extension ({@link com.digitalpetri.iec60870.test.common.ParanoidLeakDetection}).
 *
 * <p>This module is internal test scaffolding and is never published.
 */
@NullMarked
package com.digitalpetri.iec60870.test.common;

import org.jspecify.annotations.NullMarked;
