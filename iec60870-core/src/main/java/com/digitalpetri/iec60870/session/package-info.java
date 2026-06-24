/**
 * The protocol-neutral session SPI shared by every IEC 60870-5 link/session implementation.
 *
 * <p>{@link com.digitalpetri.iec60870.session.Session} is the {@link
 * com.digitalpetri.iec60870.asdu.Asdu}-typed seam between a transport connection and the
 * application layer: it owns the per-connection flow control and data-transfer lifecycle, accepts
 * outbound ASDUs, and reports delivered ASDUs and lifecycle transitions through {@link
 * com.digitalpetri.iec60870.session.Session.Events}. Concrete implementations (the 104 {@code
 * ApciSession}, a future 101 link layer) keep their wire frame types private to their own module;
 * only {@code Asdu} crosses this boundary.
 */
@NullMarked
package com.digitalpetri.iec60870.session;

import org.jspecify.annotations.NullMarked;
