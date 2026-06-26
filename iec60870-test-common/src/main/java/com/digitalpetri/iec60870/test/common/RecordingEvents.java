package com.digitalpetri.iec60870.test.common;

import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.session.Session;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Session.Events} sink that records delivered ASDUs, data-transfer transitions, and
 * closure, exposing them for assertions.
 *
 * <p>{@link #onConnectionLost(Throwable)} is not overridden, so it follows the SPI default and is
 * counted as an {@link #onClosed(Throwable) onClosed}. Recording is unsynchronized: it is intended
 * for tests that observe the session on a single thread (for example one driven by a {@link
 * ManualScheduler} virtual clock).
 */
public final class RecordingEvents implements Session.Events {

  private final List<Asdu> asdus = new ArrayList<>();
  private final List<Boolean> dataTransferChanges = new ArrayList<>();
  private int closedCount;
  private @Nullable Throwable lastCloseCause;

  @Override
  public void onAsdu(Asdu asdu) {
    asdus.add(asdu);
  }

  @Override
  public void onDataTransferStateChanged(boolean started) {
    dataTransferChanges.add(started);
  }

  @Override
  public void onClosed(@Nullable Throwable cause) {
    closedCount++;
    lastCloseCause = cause;
  }

  /**
   * Returns the live, append-only list of ASDUs delivered through {@link #onAsdu(Asdu)}, in receive
   * order.
   *
   * @return the recorded ASDUs.
   */
  public List<Asdu> asdus() {
    return asdus;
  }

  /**
   * Returns the live, append-only list of data-transfer transitions reported through {@link
   * #onDataTransferStateChanged(boolean)}, in order ({@code true} = started, {@code false} =
   * stopped).
   *
   * @return the recorded data-transfer transitions.
   */
  public List<Boolean> dataTransferChanges() {
    return dataTransferChanges;
  }

  /**
   * Returns how many times {@link #onClosed(Throwable)} has been invoked (including via the default
   * {@link #onConnectionLost(Throwable)} delegation).
   *
   * @return the close count.
   */
  public int closedCount() {
    return closedCount;
  }

  /**
   * Returns the cause reported by the most recent {@link #onClosed(Throwable)}, or {@code null} if
   * the session has not closed or closed orderly.
   *
   * @return the last close cause, or {@code null}.
   */
  public @Nullable Throwable lastCloseCause() {
    return lastCloseCause;
  }
}
