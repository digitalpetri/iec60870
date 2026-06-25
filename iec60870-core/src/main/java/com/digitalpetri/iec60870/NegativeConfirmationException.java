package com.digitalpetri.iec60870;

import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.Cause;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when a peer answers a request with a negative confirmation, that is a confirming ASDU
 * whose P/N (positive/negative) bit is set.
 *
 * <p>A negative confirmation reports that the controlled station rejected the request (for example
 * a command activation or interrogation). The {@linkplain #cause() cause of transmission} from the
 * confirming ASDU identifies the kind of confirmation, and the {@linkplain #asdu() confirming ASDU}
 * is carried when available so callers can inspect the addresses and any qualifier the station
 * returned.
 */
public class NegativeConfirmationException extends Iec60870Exception {

  /** The cause of transmission from the negative confirmation. */
  private final Cause cause;

  /** The confirming ASDU, or {@code null} if not available. */
  private final transient @Nullable Asdu asdu;

  /**
   * Creates an exception for a negative confirmation, without the confirming ASDU.
   *
   * @param cause the cause of transmission from the negative confirmation.
   */
  public NegativeConfirmationException(Cause cause) {
    this(cause, null);
  }

  /**
   * Creates an exception for a negative confirmation.
   *
   * @param cause the cause of transmission from the negative confirmation.
   * @param asdu the confirming ASDU, or {@code null} if it is not available.
   */
  public NegativeConfirmationException(Cause cause, @Nullable Asdu asdu) {
    super("negative confirmation: " + cause);
    this.cause = cause;
    this.asdu = asdu;
  }

  /**
   * Returns the cause of transmission carried by the negative confirmation.
   *
   * @return the cause of transmission.
   */
  public Cause cause() {
    return cause;
  }

  /**
   * Returns the confirming ASDU, if it was available when the exception was created.
   *
   * @return an {@link Optional} containing the confirming ASDU, or an empty {@link Optional} if
   *     none was supplied.
   */
  public Optional<Asdu> asdu() {
    return Optional.ofNullable(asdu);
  }
}
