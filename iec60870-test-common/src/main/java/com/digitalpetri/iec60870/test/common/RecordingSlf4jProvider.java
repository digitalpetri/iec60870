package com.digitalpetri.iec60870.test.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.simple.SimpleServiceProvider;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * An SLF4J provider for tests that keeps slf4j-simple's console output, gives the MDC a working
 * adapter, and records library log calls with the MDC in effect while recording is on.
 *
 * <p>A module selects it with the {@code slf4j.provider} system property in its surefire
 * configuration. While {@link #startRecording(String)} is active, every logger whose name starts
 * with the prefix reports trace as enabled and each call is recorded, whatever slf4j-simple's own
 * level; slf4j-simple still prints only what its level enables.
 */
public final class RecordingSlf4jProvider implements SLF4JServiceProvider {

  private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
  private static volatile @Nullable String recordingPrefix;

  private final SimpleServiceProvider delegate = new SimpleServiceProvider();
  private final MDCAdapter mdcAdapter = new BasicMDCAdapter();
  private final Map<String, Logger> loggers = new ConcurrentHashMap<>();
  private final ILoggerFactory loggerFactory =
      name ->
          loggers.computeIfAbsent(
              name, n -> new RecordingLogger(n, delegate.getLoggerFactory().getLogger(n)));

  /**
   * Starts recording the log calls of loggers whose names start with {@code prefix}, clearing
   * earlier records.
   *
   * @param prefix the logger name prefix.
   */
  public static void startRecording(String prefix) {
    ENTRIES.clear();
    recordingPrefix = prefix;
  }

  /**
   * Stops recording and returns what was recorded.
   *
   * @return the recorded calls, oldest first.
   */
  public static List<Entry> stopRecording() {
    recordingPrefix = null;
    return List.copyOf(ENTRIES);
  }

  /**
   * Reports whether this provider is the active SLF4J provider.
   *
   * @return {@code true} if MDC entries and log calls can be observed.
   */
  public static boolean isActive() {
    return MDC.getMDCAdapter() instanceof BasicMDCAdapter;
  }

  @Override
  public ILoggerFactory getLoggerFactory() {
    return loggerFactory;
  }

  @Override
  public IMarkerFactory getMarkerFactory() {
    return delegate.getMarkerFactory();
  }

  @Override
  public MDCAdapter getMDCAdapter() {
    return mdcAdapter;
  }

  @Override
  public String getRequestedApiVersion() {
    return delegate.getRequestedApiVersion();
  }

  @Override
  public void initialize() {
    delegate.initialize();
  }

  /**
   * One recorded log call.
   *
   * @param logger the logger name.
   * @param level the level.
   * @param message the formatted message.
   * @param mdc the MDC entries in effect during the call.
   * @param thread the name of the logging thread.
   */
  public record Entry(
      String logger, Level level, String message, Map<String, String> mdc, String thread) {}

  private static final class RecordingLogger extends LegacyAbstractLogger {

    private final Logger delegate;

    private RecordingLogger(String name, Logger delegate) {
      this.name = name;
      this.delegate = delegate;
    }

    private boolean recording() {
      String prefix = recordingPrefix;
      return prefix != null && name.startsWith(prefix);
    }

    @Override
    public boolean isTraceEnabled() {
      return recording() || delegate.isTraceEnabled();
    }

    @Override
    public boolean isDebugEnabled() {
      return recording() || delegate.isDebugEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
      return recording() || delegate.isInfoEnabled();
    }

    @Override
    public boolean isWarnEnabled() {
      return recording() || delegate.isWarnEnabled();
    }

    @Override
    public boolean isErrorEnabled() {
      return recording() || delegate.isErrorEnabled();
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
      String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
      if (recording()) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        ENTRIES.add(
            new Entry(
                name,
                level,
                message,
                mdc != null ? Map.copyOf(mdc) : Map.of(),
                Thread.currentThread().getName()));
      }
      if (delegate.isEnabledForLevel(level)) {
        delegate.makeLoggingEventBuilder(level).setMessage(message).setCause(throwable).log();
      }
    }
  }
}
