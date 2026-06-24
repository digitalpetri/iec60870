package com.digitalpetri.iec104.client;

import com.digitalpetri.iec104.apci.Apdu;
import com.digitalpetri.iec104.apci.ControlField;
import com.digitalpetri.iec104.apci.UFunction;
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.transport.ClientTransport;
import com.digitalpetri.iec104.transport.TransportListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * An in-memory {@link ClientTransport} for client unit tests.
 *
 * <p>Captures every APDU the client sends, lets the test inject inbound APDUs, and tracks the
 * sequence numbers so that injected I-frames carry valid N(S)/N(R) values. The transport answers a
 * STARTDT activation automatically so {@code connect()} completes, and exposes helpers for replying
 * with application ASDUs.
 */
final class FakeClientTransport implements ClientTransport {

  private final List<Apdu> sent = new ArrayList<>();
  private @Nullable TransportListener listener;
  private boolean connected;

  /**
   * One-shot guard mirroring Netty: channelInactive fires onConnectionLost(null) once per connect.
   */
  private boolean connectionLostFired = true;

  /** The send sequence number the test will use for the next injected I-frame (peer V(S)). */
  private int peerSendSequence;

  /** Whether to auto-reply to a STARTDT activation with a STARTDT confirmation. */
  private boolean autoStartDt = true;

  /** When non-null, {@link #connect()} fails with this cause. */
  private @Nullable Throwable connectFailure;

  /** When non-null, every {@link #send(Apdu)} fails with this cause. */
  private @Nullable Throwable sendFailure;

  @Override
  public CompletionStage<Void> connect() {
    Throwable failure = connectFailure;
    if (failure != null) {
      return CompletableFuture.failedFuture(failure);
    }
    connected = true;
    connectionLostFired = false;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> disconnect() {
    boolean wasConnected = connected;
    connected = false;
    // Mirror Netty's channelInactive: disconnecting an open channel drives onConnectionLost(null)
    // exactly once. Reset on each connect() so a reconnect can fire it again.
    if (wasConnected && !connectionLostFired) {
      connectionLostFired = true;
      TransportListener current = listener;
      if (current != null) {
        current.onConnectionLost(null);
      }
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public boolean isConnected() {
    return connected;
  }

  @Override
  public CompletionStage<Void> send(Apdu apdu) {
    Throwable failure = sendFailure;
    if (failure != null) {
      return CompletableFuture.failedFuture(failure);
    }
    sent.add(apdu);
    if (autoStartDt
        && apdu.control() instanceof ControlField.TypeU u
        && u.function() == UFunction.STARTDT_ACT) {
      deliver(new Apdu(new ControlField.TypeU(UFunction.STARTDT_CON), null));
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void setListener(TransportListener listener) {
    this.listener = listener;
  }

  /** Disables the automatic STARTDT confirmation. */
  // Intentional test-fixture API: the only control of the documented auto-STARTDT behavior.
  @SuppressWarnings("unused")
  void disableAutoStartDt() {
    this.autoStartDt = false;
  }

  /**
   * Makes the next and subsequent {@link #connect()} calls fail with the given cause.
   *
   * @param cause the failure to report from {@code connect()}.
   */
  void failConnect(Throwable cause) {
    this.connectFailure = cause;
  }

  /**
   * Makes every {@link #send(Apdu)} fail with the given cause until cleared.
   *
   * @param cause the failure to report from {@code send(Apdu)}.
   */
  void failSend(Throwable cause) {
    this.sendFailure = cause;
  }

  /**
   * Delivers an inbound APDU to the registered listener.
   *
   * @param apdu the APDU to deliver.
   */
  void deliver(Apdu apdu) {
    requireListener().onApdu(apdu);
  }

  /**
   * Delivers an application ASDU as an I-frame with valid sequence numbers.
   *
   * @param asdu the ASDU to deliver.
   */
  void deliverAsdu(Asdu asdu) {
    int ns = peerSendSequence++;
    deliver(new Apdu(new ControlField.TypeI(ns, 0), asdu));
  }

  /**
   * Returns the N(S) that the next {@link #deliverAsdu(Asdu)} injected I-frame will carry.
   *
   * @return the peer send sequence number for the next injected I-frame.
   */
  int peerSendSequenceForNext() {
    return peerSendSequence;
  }

  /**
   * Delivers an I-frame with a valid N(S) but an N(R) that acknowledges far more frames than the
   * client ever sent, driving a fatal {@code SequenceNumberException} self-close in the session.
   */
  void deliverBadAcknowledgement(Asdu asdu) {
    int ns = peerSendSequence++;
    deliver(new Apdu(new ControlField.TypeI(ns, 9999), asdu));
  }

  /** Signals connection loss to the listener. */
  void loseConnection() {
    connected = false;
    requireListener().onConnectionLost(new RuntimeException("connection lost"));
  }

  /**
   * Returns the application ASDUs the client has sent so far.
   *
   * @return the sent ASDUs in order.
   */
  List<Asdu> sentAsdus() {
    List<Asdu> asdus = new ArrayList<>();
    for (Apdu apdu : sent) {
      if (apdu.control() instanceof ControlField.TypeI && apdu.asdu() != null) {
        asdus.add(apdu.asdu());
      }
    }
    return asdus;
  }

  /**
   * Returns the I-format control fields of the application I-frames the client has sent, in order,
   * so a test can inspect their N(S)/N(R) sequence numbers.
   *
   * @return the sent I-frame control fields in order.
   */
  List<ControlField.TypeI> sentIFrameControls() {
    List<ControlField.TypeI> controls = new ArrayList<>();
    for (Apdu apdu : sent) {
      if (apdu.control() instanceof ControlField.TypeI i) {
        controls.add(i);
      }
    }
    return controls;
  }

  private TransportListener requireListener() {
    TransportListener current = listener;
    if (current == null) {
      throw new IllegalStateException("no listener registered");
    }
    return current;
  }
}
