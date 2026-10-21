package com.digitalpetri.iec60870.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

class LoggingContextTest {

  private final CapturingLogger logger = new CapturingLogger();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void entriesAreInTheMdcOnlyDuringTheLogCall() {
    LoggingContext context =
        LoggingContext.of(Map.of("device", "rtu-5"))
            .withRemote(InetSocketAddress.createUnresolved("10.0.0.5", 2404));

    context.debug(logger, "{}: hello {}", context.remote(), "world");

    assertEquals(1, logger.calls.size());
    assertEquals("10.0.0.5:2404: hello world", logger.calls.get(0).message());
    assertEquals(
        Map.of("device", "rtu-5", LoggingContext.REMOTE_KEY, "10.0.0.5:2404"),
        logger.calls.get(0).mdc());
    assertNull(MDC.get("device"));
    assertNull(MDC.get(LoggingContext.REMOTE_KEY));
  }

  @Test
  void previousMdcValuesAreRestored() {
    MDC.put("device", "outer");

    LoggingContext.of(Map.of("device", "inner")).warn(logger, "message");

    assertEquals("inner", logger.calls.get(0).mdc().get("device"));
    assertEquals("outer", MDC.get("device"));
  }

  @Test
  void disabledLevelsDoNotTouchTheMdcOrLog() {
    logger.threshold = Level.INFO;
    LoggingContext context = LoggingContext.of(Map.of("device", "rtu-5"));

    context.debug(logger, "hidden");
    context.trace(logger, "hidden");
    context.info(logger, "shown");

    assertEquals(List.of("shown"), logger.calls.stream().map(Call::message).toList());
  }

  @Test
  void trailingThrowableIsLoggedAsTheCause() {
    var failure = new IllegalStateException("boom");

    LoggingContext.empty().debug(logger, "failed for {}", "rtu", failure);

    assertEquals("failed for rtu", logger.calls.get(0).message());
    assertSame(failure, logger.calls.get(0).throwable());
  }

  @Test
  void withRemoteKeepsAnExplicitRemoteEntry() {
    LoggingContext explicit = LoggingContext.of(Map.of(LoggingContext.REMOTE_KEY, "gateway"));

    assertSame(explicit, explicit.withRemote(InetSocketAddress.createUnresolved("h", 1)));
    assertEquals("gateway", explicit.remote());
    assertEquals("-", LoggingContext.empty().remote());
    assertTrue(LoggingContext.of(Map.of()).entries().isEmpty());
  }

  private record Call(String message, Map<String, String> mdc, @Nullable Throwable throwable) {}

  /** Records enabled log calls with the MDC in effect at the time. */
  private static final class CapturingLogger extends LegacyAbstractLogger {

    private final List<Call> calls = new ArrayList<>();
    private Level threshold = Level.TRACE;

    private CapturingLogger() {
      this.name = "test";
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
      calls.add(
          new Call(
              MessageFormatter.basicArrayFormat(messagePattern, arguments),
              mdc != null ? Map.copyOf(mdc) : Map.of(),
              throwable));
    }
  }
}
