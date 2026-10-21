package com.digitalpetri.iec60870.diagnostics;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * The SLF4J MDC entries that identify one connection in the library's log output.
 *
 * <p>Library components that log on behalf of a connection call {@link #debug(Logger, String,
 * Object...)} and its siblings instead of the logger directly. When the level is enabled, the call
 * puts every entry into the MDC, logs, and then restores the MDC values that were present before,
 * so the entries never remain on a shared I/O or scheduler thread. When the level is disabled the
 * MDC is not touched.
 *
 * <p>Applications supply the entries as a {@code Map<String, String>} on the builders and
 * configuration records. The TCP client builders add {@value #REMOTE_KEY} with the configured
 * {@code host:port}, and a server adds it with the address of each accepted connection.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class LoggingContext {

  /** The MDC key that carries the remote address of a connection. */
  public static final String REMOTE_KEY = "iec60870.remote";

  private static final LoggingContext EMPTY = new LoggingContext(Map.of());

  private static final Scope NO_SCOPE = () -> {};

  private final Map<String, String> entries;

  private LoggingContext(Map<String, String> entries) {
    this.entries = entries;
  }

  /**
   * Returns a context without entries, which never touches the MDC.
   *
   * @return the empty context.
   */
  public static LoggingContext empty() {
    return EMPTY;
  }

  /**
   * Returns a context with the given entries, in the map's iteration order.
   *
   * @param entries the MDC entries; neither keys nor values may be null.
   * @return the context.
   * @throws NullPointerException if {@code entries} or any key or value is null.
   */
  public static LoggingContext of(Map<String, String> entries) {
    Objects.requireNonNull(entries, "entries");
    if (entries.isEmpty()) {
      return EMPTY;
    }
    var copy = new LinkedHashMap<String, String>();
    entries.forEach(
        (key, value) ->
            copy.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value")));
    return new LoggingContext(Collections.unmodifiableMap(copy));
  }

  /**
   * Returns a context with {@code key} set to {@code value}, replacing an existing entry.
   *
   * @param key the MDC key.
   * @param value the MDC value.
   * @return the new context.
   */
  public LoggingContext with(String key, String value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    var copy = new LinkedHashMap<>(entries);
    copy.put(key, value);
    return new LoggingContext(Collections.unmodifiableMap(copy));
  }

  /**
   * Returns a context with {@value #REMOTE_KEY} set to {@code remote}, unless this context already
   * sets that key.
   *
   * @param remote the remote address of the connection.
   * @return the new context, or this context if it already names a remote address.
   */
  public LoggingContext withRemote(SocketAddress remote) {
    Objects.requireNonNull(remote, "remote");
    if (entries.containsKey(REMOTE_KEY)) {
      return this;
    }
    return with(REMOTE_KEY, format(remote));
  }

  /**
   * Returns the entries.
   *
   * @return an unmodifiable view of the MDC entries.
   */
  public Map<String, String> entries() {
    return entries;
  }

  /**
   * Returns the remote address entry, for log messages written for patterns that omit the MDC.
   *
   * @return the {@value #REMOTE_KEY} value, or {@code "-"} if the context has none.
   */
  public String remote() {
    return entries.getOrDefault(REMOTE_KEY, "-");
  }

  /**
   * Formats a socket address as {@code host:port}, without resolving it.
   *
   * @param address the address.
   * @return {@code host:port} for an {@link InetSocketAddress}, otherwise {@code toString()}.
   */
  public static String format(SocketAddress address) {
    if (address instanceof InetSocketAddress inet) {
      return inet.getHostString() + ":" + inet.getPort();
    }
    return address.toString();
  }

  /**
   * Puts the entries into the MDC until the returned scope is closed.
   *
   * <p>Closing the scope restores the values the MDC held for these keys before, removing keys that
   * were absent. Open and close the scope on the same thread.
   *
   * @return the scope to close.
   */
  public Scope open() {
    if (entries.isEmpty()) {
      return NO_SCOPE;
    }
    String[] keys = entries.keySet().toArray(new String[0]);
    @Nullable String[] previous = new String[keys.length];
    for (int i = 0; i < keys.length; i++) {
      previous[i] = MDC.get(keys[i]);
      MDC.put(keys[i], entries.get(keys[i]));
    }
    return () -> {
      for (int i = 0; i < keys.length; i++) {
        String value = previous[i];
        if (value == null) {
          MDC.remove(keys[i]);
        } else {
          MDC.put(keys[i], value);
        }
      }
    };
  }

  /**
   * Logs at trace level with the entries in the MDC, if trace is enabled.
   *
   * @param logger the logger.
   * @param format the SLF4J message format.
   * @param arguments the message arguments; a trailing {@link Throwable} is logged as the cause.
   */
  public void trace(Logger logger, String format, @Nullable Object... arguments) {
    if (logger.isTraceEnabled()) {
      try (Scope ignored = open()) {
        logger.trace(format, arguments);
      }
    }
  }

  /**
   * Logs at debug level with the entries in the MDC, if debug is enabled.
   *
   * @param logger the logger.
   * @param format the SLF4J message format.
   * @param arguments the message arguments; a trailing {@link Throwable} is logged as the cause.
   */
  public void debug(Logger logger, String format, @Nullable Object... arguments) {
    if (logger.isDebugEnabled()) {
      try (Scope ignored = open()) {
        logger.debug(format, arguments);
      }
    }
  }

  /**
   * Logs at info level with the entries in the MDC, if info is enabled.
   *
   * @param logger the logger.
   * @param format the SLF4J message format.
   * @param arguments the message arguments; a trailing {@link Throwable} is logged as the cause.
   */
  public void info(Logger logger, String format, @Nullable Object... arguments) {
    if (logger.isInfoEnabled()) {
      try (Scope ignored = open()) {
        logger.info(format, arguments);
      }
    }
  }

  /**
   * Logs at warn level with the entries in the MDC, if warn is enabled.
   *
   * @param logger the logger.
   * @param format the SLF4J message format.
   * @param arguments the message arguments; a trailing {@link Throwable} is logged as the cause.
   */
  public void warn(Logger logger, String format, @Nullable Object... arguments) {
    if (logger.isWarnEnabled()) {
      try (Scope ignored = open()) {
        logger.warn(format, arguments);
      }
    }
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof LoggingContext that && entries.equals(that.entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }

  @Override
  public String toString() {
    return "LoggingContext" + entries;
  }

  /** The MDC entries of a {@link LoggingContext}, in place until {@link #close()}. */
  @FunctionalInterface
  public interface Scope extends AutoCloseable {

    /** Restores the MDC values that were present before the scope opened. */
    @Override
    void close();
  }
}
