package com.digitalpetri.iec60870.diagnostics;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.simple.SimpleServiceProvider;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * The slf4j-simple provider with a working MDC, selected for this module's tests through the {@code
 * slf4j.provider} system property; slf4j-simple's own MDC adapter discards every entry.
 */
public final class MdcServiceProvider implements SLF4JServiceProvider {

  private final SimpleServiceProvider delegate = new SimpleServiceProvider();
  private final MDCAdapter mdcAdapter = new BasicMDCAdapter();

  @Override
  public ILoggerFactory getLoggerFactory() {
    return delegate.getLoggerFactory();
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
}
