package com.digitalpetri.iec60870.test.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * An SLF4J logger that records each enabled log call with the MDC in effect at the time.
 *
 * <p>Tests hand it to code that accepts a {@link org.slf4j.Logger} to assert what was logged, at
 * which level, and with which MDC entries. The enabled threshold can change at runtime.
 */
public final class CapturingLogger extends LegacyAbstractLogger {

  private final List<Entry> entries = new CopyOnWriteArrayList<>();
  private volatile Level threshold;

  /**
   * Creates a logger.
   *
   * @param name the logger name.
   * @param threshold the most verbose enabled level.
   */
  public CapturingLogger(String name, Level threshold) {
    this.name = name;
    this.threshold = threshold;
  }

  /**
   * Sets the most verbose enabled level.
   *
   * @param threshold the level.
   */
  public void setThreshold(Level threshold) {
    this.threshold = threshold;
  }

  /**
   * Returns the recorded log calls.
   *
   * @return the entries, oldest first.
   */
  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  private boolean enabled(Level level) {
    return level.toInt() >= threshold.toInt();
  }

  @Override
  public boolean isTraceEnabled() {
    return enabled(Level.TRACE);
  }

  @Override
  public boolean isDebugEnabled() {
    return enabled(Level.DEBUG);
  }

  @Override
  public boolean isInfoEnabled() {
    return enabled(Level.INFO);
  }

  @Override
  public boolean isWarnEnabled() {
    return enabled(Level.WARN);
  }

  @Override
  public boolean isErrorEnabled() {
    return enabled(Level.ERROR);
  }

  @Override
  protected @Nullable String getFullyQualifiedCallerName() {
    return null;
  }

  @Override
  protected void handleNormalizedLoggingCall(
      Level level,
      @Nullable Marker marker,
      String messagePattern,
      Object @Nullable [] arguments,
      @Nullable Throwable throwable) {
    Map<String, String> mdc = MDC.getCopyOfContextMap();
    entries.add(
        new Entry(
            level,
            MessageFormatter.basicArrayFormat(messagePattern, arguments),
            mdc != null ? Map.copyOf(mdc) : Map.of(),
            throwable));
  }

  /**
   * One recorded log call.
   *
   * @param level the level.
   * @param message the formatted message.
   * @param mdc the MDC entries in effect during the call.
   * @param throwable the logged throwable, if any.
   */
  public record Entry(
      Level level, String message, Map<String, String> mdc, @Nullable Throwable throwable) {}
}
