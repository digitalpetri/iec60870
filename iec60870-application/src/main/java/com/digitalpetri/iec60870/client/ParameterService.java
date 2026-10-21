package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfParameterActivation;
import java.util.concurrent.CompletionStage;

/**
 * Loads and activates parameters of measured values in a controlled station (P_ME_NA_1 through
 * P_AC_NA_1).
 *
 * <p>Obtain the service from {@link Iec60870Client#parameters()}. Each call sends one activation
 * and completes on the station's matching confirmation, or fails after {@link
 * ClientConfig#commandTimeout()}. One load and one activation may be in flight per parameter
 * address; a second fails with {@link com.digitalpetri.iec60870.RequestInProgressException} without
 * sending anything. A connection loss fails every call in flight with {@link
 * com.digitalpetri.iec60870.ConnectionClosedException}.
 *
 * <p>Confirmations must carry the same type and address as the activation, the client's originator
 * address or zero, and the same test flag. Parameter ASDUs sent in response to an interrogation
 * (causes 20 to 36) never complete a load.
 *
 * <pre>{@code
 * PointAddress limit = PointAddress.of(1, 4001);
 * ParameterResult result = client.parameters()
 *     .load(new ParameterRequest.ShortFloat(limit, 80.0f, new QualifierOfParameter(4, false, false)));
 * if (result.positive()) {
 *   client.parameters().activate(limit, QualifierOfParameterActivation.of(3));
 * }
 * }</pre>
 */
public interface ParameterService {

  /**
   * Loads a parameter and waits for the station's activation confirmation.
   *
   * @param request the parameter to load.
   * @return the outcome, positive or negative, with the parameter now in operation.
   * @throws IllegalArgumentException if the target's common address is the global address.
   * @throws com.digitalpetri.iec60870.RequestInProgressException if a load of the same parameter is
   *     already in flight.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if the confirmation does not arrive
   *     within the command timeout.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed.
   */
  ParameterResult load(ParameterRequest request);

  /**
   * Loads a parameter and returns a stage for the station's activation confirmation.
   *
   * @param request the parameter to load.
   * @return a stage that completes with the outcome, or completes exceptionally on timeout,
   *     conflict, or disconnect.
   * @throws IllegalArgumentException if the target's common address is the global address.
   */
  CompletionStage<ParameterResult> loadAsync(ParameterRequest request);

  /**
   * Activates a parameter (P_AC_NA_1 with cause activation) and waits for the confirmation.
   *
   * @param target the address of the parameter or object to act on.
   * @param qpa the qualifier of parameter activation.
   * @throws IllegalArgumentException if the target's common address is the global address.
   * @throws com.digitalpetri.iec60870.NegativeConfirmationException if the station rejects the
   *     activation or reports an unknown type, cause, or address.
   * @throws com.digitalpetri.iec60870.RequestInProgressException if a parameter activation for the
   *     same address is already in flight.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if the confirmation does not arrive
   *     within the command timeout.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed.
   */
  void activate(PointAddress target, QualifierOfParameterActivation qpa);

  /**
   * Activates a parameter (P_AC_NA_1 with cause activation) and returns a stage for the
   * confirmation.
   *
   * @param target the address of the parameter or object to act on.
   * @param qpa the qualifier of parameter activation.
   * @return a stage that completes when the station confirms positively, or completes exceptionally
   *     on rejection, timeout, conflict, or disconnect.
   * @throws IllegalArgumentException if the target's common address is the global address.
   */
  CompletionStage<Void> activateAsync(PointAddress target, QualifierOfParameterActivation qpa);

  /**
   * Deactivates a parameter (P_AC_NA_1 with cause deactivation) and waits for the deactivation
   * confirmation.
   *
   * @param target the address of the parameter or object to act on.
   * @param qpa the qualifier of parameter activation.
   * @throws IllegalArgumentException if the target's common address is the global address.
   * @throws com.digitalpetri.iec60870.NegativeConfirmationException if the station rejects the
   *     deactivation or reports an unknown type, cause, or address.
   * @throws com.digitalpetri.iec60870.RequestInProgressException if a parameter activation for the
   *     same address is already in flight.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if the confirmation does not arrive
   *     within the command timeout.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed.
   */
  void deactivate(PointAddress target, QualifierOfParameterActivation qpa);

  /**
   * Deactivates a parameter (P_AC_NA_1 with cause deactivation) and returns a stage for the
   * deactivation confirmation.
   *
   * @param target the address of the parameter or object to act on.
   * @param qpa the qualifier of parameter activation.
   * @return a stage that completes when the station confirms positively, or completes exceptionally
   *     on rejection, timeout, conflict, or disconnect.
   * @throws IllegalArgumentException if the target's common address is the global address.
   */
  CompletionStage<Void> deactivateAsync(PointAddress target, QualifierOfParameterActivation qpa);
}
