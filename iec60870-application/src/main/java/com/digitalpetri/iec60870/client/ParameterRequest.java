package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.QualifierOfParameter;
import java.util.Objects;

/**
 * A parameter of measured values to load into a controlled station (P_ME_NA_1, P_ME_NB_1, or
 * P_ME_NC_1).
 *
 * <p>The {@link #target()} names the parameter: its common address and the information object
 * address of the parameter. The {@link #qualifier()} selects the kind of parameter (threshold,
 * smoothing factor, low or high limit) and carries the LPC and POP flags.
 *
 * <pre>{@code
 * var threshold = new QualifierOfParameter(1, false, false);
 * ParameterResult result =
 *     client.parameters().load(new ParameterRequest.ShortFloat(target, 0.5f, threshold));
 * }</pre>
 *
 * @see ParameterService#load(ParameterRequest)
 */
public sealed interface ParameterRequest {

  /**
   * Returns the address of the parameter.
   *
   * @return the common address and information object address of the parameter.
   */
  PointAddress target();

  /**
   * Returns the qualifier of the parameter.
   *
   * @return the qualifier of parameter of measured values (QPM).
   */
  QualifierOfParameter qualifier();

  /**
   * A normalized parameter, sent as P_ME_NA_1.
   *
   * @param target the address of the parameter.
   * @param value the normalized parameter value.
   * @param qualifier the qualifier of parameter of measured values.
   */
  record Normalized(PointAddress target, NormalizedValue value, QualifierOfParameter qualifier)
      implements ParameterRequest {

    /**
     * Validates the components of the request.
     *
     * @param target the address of the parameter.
     * @param value the normalized parameter value.
     * @param qualifier the qualifier of parameter of measured values.
     * @throws NullPointerException if any component is null.
     */
    public Normalized {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(qualifier, "qualifier");
    }
  }

  /**
   * A scaled parameter, sent as P_ME_NB_1.
   *
   * @param target the address of the parameter.
   * @param value the scaled parameter value.
   * @param qualifier the qualifier of parameter of measured values.
   */
  record Scaled(PointAddress target, short value, QualifierOfParameter qualifier)
      implements ParameterRequest {

    /**
     * Validates the components of the request.
     *
     * @param target the address of the parameter.
     * @param value the scaled parameter value.
     * @param qualifier the qualifier of parameter of measured values.
     * @throws NullPointerException if any component is null.
     */
    public Scaled {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(qualifier, "qualifier");
    }
  }

  /**
   * A short floating-point parameter, sent as P_ME_NC_1.
   *
   * @param target the address of the parameter.
   * @param value the short floating-point parameter value.
   * @param qualifier the qualifier of parameter of measured values.
   */
  record ShortFloat(PointAddress target, float value, QualifierOfParameter qualifier)
      implements ParameterRequest {

    /**
     * Validates the components of the request.
     *
     * @param target the address of the parameter.
     * @param value the short floating-point parameter value.
     * @param qualifier the qualifier of parameter of measured values.
     * @throws NullPointerException if any component is null.
     */
    public ShortFloat {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(qualifier, "qualifier");
    }
  }
}
