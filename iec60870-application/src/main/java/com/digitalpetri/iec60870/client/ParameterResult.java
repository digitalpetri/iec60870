package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a parameter load (P_ME_NA_1, P_ME_NB_1, or P_ME_NC_1).
 *
 * <p>A controlled station answers a parameter load with an activation confirmation that carries the
 * parameter now in operation: the new value when it accepted the load, the old value when it
 * rejected it (IEC 60870-5-101, 7.4.9). A rejection is therefore a normal outcome, reported with
 * {@link #positive()} {@code false} rather than an exception, as for {@link CommandResult}.
 *
 * <p>{@link #valueInOperation()} is present when the station answered with cause {@link
 * Cause#ACTIVATION_CONFIRMATION}, positive or negative. It is empty when the station reported an
 * unknown type, cause, common address, or information object address (causes 44 to 47); {@link
 * #positive()} is then {@code false}.
 *
 * @param target the parameter that was loaded.
 * @param positive {@code true} if the station confirmed the load positively.
 * @param cause the cause of transmission of the confirming ASDU.
 * @param valueInOperation the parameter object in operation, as reported by the station.
 * @param confirmation the confirming ASDU.
 */
public record ParameterResult(
    PointAddress target,
    boolean positive,
    Cause cause,
    Optional<InformationObject> valueInOperation,
    Asdu confirmation) {

  /**
   * Validates the components of the result.
   *
   * @param target the parameter that was loaded.
   * @param positive {@code true} if the station confirmed the load positively.
   * @param cause the cause of transmission of the confirming ASDU.
   * @param valueInOperation the parameter object in operation, as reported by the station.
   * @param confirmation the confirming ASDU.
   * @throws NullPointerException if any component is null.
   */
  public ParameterResult {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(cause, "cause");
    Objects.requireNonNull(valueInOperation, "valueInOperation");
    Objects.requireNonNull(confirmation, "confirmation");
  }
}
