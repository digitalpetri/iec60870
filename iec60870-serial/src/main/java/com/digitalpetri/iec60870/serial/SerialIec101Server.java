package com.digitalpetri.iec60870.serial;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.cs101.Cs101Binding;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.server.DefaultIec60870Server;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.ServerConfig;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.transport.serial.Rs485Options;
import com.digitalpetri.iec60870.transport.serial.SerialPortConfig;
import com.digitalpetri.iec60870.transport.serial.SerialServerTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * User-facing entry point that builds a serial-backed IEC 60870-5-101 {@link Iec60870Server} for a
 * balanced point-to-point outstation.
 *
 * <p>This is the serial peer of {@code TcpIec104Server}: it is the sole assembly point that wires
 * the serial octet transport to the CS101 link layer and the high-level server facade. A serial
 * line connects exactly two stations, so the transport accepts a single connection — there is no
 * bind address, port, TLS, or multi-connection cap. {@link Builder#build()} constructs a {@link
 * SerialServerTransport} plus a {@link DefaultIec60870Server} and returns the core {@link
 * Iec60870Server} interface.
 *
 * <p>The builder holds no link-layer wiring itself: it delegates the full FT1.2 link-engine plus
 * framing assembly to {@link Cs101Binding}, so no {@code Ft12LinkLayer} is constructed and no
 * framer is invoked here.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * Station station = Station.builder(CommonAddress.of(1))
 *     .point(definition)
 *     .build();
 *
 * try (Iec60870Server server = SerialIec101Server.builder()
 *         .serialPort("/dev/ttyUSB0")
 *         .addStation(station)
 *         .handler(myHandler)
 *         .build()) {
 *     server.start();
 *     server.publish(point, PointValue.scaled(42, Quality.good()), Cause.SPONTANEOUS);
 * }
 * }</pre>
 */
public final class SerialIec101Server {

  private SerialIec101Server() {}

  /**
   * Returns a new builder seeded with the standard IEC 60870-5-101 8E1 defaults.
   *
   * @return a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for a serial-backed {@link Iec60870Server}.
   *
   * <p>Transport knobs ({@code serialPort}, {@code baudRate}, {@code dataBits}, {@code parity},
   * {@code stopBits}, {@code readTimeout}, {@code writeTimeout}) configure the serial port;
   * protocol knobs ({@code profile}, {@code linkSettings}, {@code addStation}, {@code handler},
   * {@code callbackExecutor}) configure the core server. The builder is not thread-safe.
   */
  public static final class Builder {

    private @Nullable String portName;
    private int baudRate = 9600;
    private int dataBits = 8;
    private SerialPortConfig.Parity parity = SerialPortConfig.Parity.EVEN;
    private int stopBits = 1;
    private ProtocolProfile profile = ProtocolProfile.iec101Default();
    private LinkSettings linkSettings = LinkSettings.balanced().build();
    private final List<Station> stations = new ArrayList<>();
    private @Nullable ServerHandler handler;
    private @Nullable Executor callbackExecutor;
    private Duration readTimeout = Duration.ofMillis(100);
    private Duration writeTimeout = Duration.ofMillis(1000);
    private @Nullable Rs485Options rs485;

    private Builder() {}

    /**
     * Sets the system serial port to open, for example {@code "/dev/ttyUSB0"} or {@code "COM3"}.
     * This is required; there is no default.
     *
     * @param portName the system port name.
     * @return this builder.
     */
    public Builder serialPort(String portName) {
      this.portName = Objects.requireNonNull(portName, "portName");
      return this;
    }

    /**
     * Sets the symbol rate in bits per second. Defaults to {@code 9600}.
     *
     * @param baudRate the baud rate; must be positive.
     * @return this builder.
     */
    public Builder baudRate(int baudRate) {
      this.baudRate = baudRate;
      return this;
    }

    /**
     * Sets the number of data bits per character. Defaults to {@code 8}.
     *
     * @param dataBits the data bits, in the range 5 to 8.
     * @return this builder.
     */
    public Builder dataBits(int dataBits) {
      this.dataBits = dataBits;
      return this;
    }

    /**
     * Sets the parity scheme. Defaults to {@link SerialPortConfig.Parity#EVEN}, as required by
     * FT1.2.
     *
     * @param parity the parity scheme.
     * @return this builder.
     */
    public Builder parity(SerialPortConfig.Parity parity) {
      this.parity = Objects.requireNonNull(parity, "parity");
      return this;
    }

    /**
     * Sets the number of stop bits. Defaults to {@code 1}.
     *
     * @param stopBits the stop bits, either 1 or 2.
     * @return this builder.
     */
    public Builder stopBits(int stopBits) {
      this.stopBits = stopBits;
      return this;
    }

    /**
     * Sets the wire field widths. Defaults to {@link ProtocolProfile#iec101Default()}, the IEC
     * 60870-5-101 profile {@code (1, 1, 2, 255)}.
     *
     * <p>IEC 60870-5-101 links commonly use narrower fields than 104 (a 1-octet cause of
     * transmission, a 1-octet common address, and a 2-octet information-object address); set a
     * matching {@link ProtocolProfile} when the peer expects different widths.
     *
     * @param profile the protocol profile.
     * @return this builder.
     */
    public Builder profile(ProtocolProfile profile) {
      this.profile = Objects.requireNonNull(profile, "profile");
      return this;
    }

    /**
     * Sets the FT1.2 balanced link parameters. Defaults to {@link LinkSettings#balanced()
     * LinkSettings.balanced().build()}.
     *
     * <p>The link-address width carried by these settings sizes the FT1.2 frames the transport
     * deframes, so it is propagated to the serial port configuration automatically.
     *
     * @param linkSettings the link settings; must describe a balanced link.
     * @return this builder.
     */
    public Builder linkSettings(LinkSettings linkSettings) {
      this.linkSettings = Objects.requireNonNull(linkSettings, "linkSettings");
      return this;
    }

    /**
     * Adds a station hosted by the outstation.
     *
     * @param station the station to host; each must have a distinct common address.
     * @return this builder.
     * @throws NullPointerException if {@code station} is null.
     */
    public Builder addStation(Station station) {
      this.stations.add(Objects.requireNonNull(station, "station"));
      return this;
    }

    /**
     * Sets the handler that answers control-direction requests. Defaults to the core server's
     * default handler when unset.
     *
     * @param handler the server handler.
     * @return this builder.
     */
    public Builder handler(ServerHandler handler) {
      this.handler = Objects.requireNonNull(handler, "handler");
      return this;
    }

    /**
     * Sets the executor that delivers events and runs handler callbacks. Defaults to the core
     * server's default executor.
     *
     * @param callbackExecutor the callback executor.
     * @return this builder.
     */
    public Builder callbackExecutor(Executor callbackExecutor) {
      this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
      return this;
    }

    /**
     * Sets the maximum inactivity tolerated before a blocking read returns. Defaults to {@code
     * 100ms}.
     *
     * @param readTimeout the read timeout; must be positive.
     * @return this builder.
     */
    public Builder readTimeout(Duration readTimeout) {
      this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
      return this;
    }

    /**
     * Sets the maximum time a blocking write may take. Defaults to {@code 1000ms}.
     *
     * @param writeTimeout the write timeout; must be positive.
     * @return this builder.
     */
    public Builder writeTimeout(Duration writeTimeout) {
      this.writeTimeout = Objects.requireNonNull(writeTimeout, "writeTimeout");
      return this;
    }

    /**
     * Enables RS-485 half-duplex mode on the serial line and sets its turnaround parameters.
     * Defaults to unset, leaving the port in ordinary RS-232 / full-duplex mode.
     *
     * <p>Use this on a two-wire RS-485 multidrop bus so the driver turns the line around each
     * transmission. Note that most turnaround parameters are effective only on Linux; see {@link
     * Rs485Options}.
     *
     * @param rs485 the RS-485 options, or {@code null} to keep RS-485 mode disabled.
     * @return this builder.
     */
    public Builder rs485(@Nullable Rs485Options rs485) {
      this.rs485 = rs485;
      return this;
    }

    /**
     * Builds the serial transport and the core server and returns the {@link Iec60870Server}.
     *
     * @return the configured server.
     * @throws IllegalStateException if no serial port name was set.
     */
    public Iec60870Server build() {
      if (portName == null) {
        throw new IllegalStateException("serialPort must be set");
      }

      SerialPortConfig serialConfig =
          SerialPortConfig.builder(portName)
              .baudRate(baudRate)
              .dataBits(dataBits)
              .parity(parity)
              .stopBits(stopBits)
              .linkAddressLength(linkSettings.linkAddressLength())
              .readTimeout(readTimeout)
              .writeTimeout(writeTimeout)
              .rs485(rs485)
              .build();

      SerialServerTransport transport = new SerialServerTransport(serialConfig);

      ServerConfig.Builder serverConfigBuilder =
          ServerConfig.builder()
              .protocolProfile(profile)
              .sessionSettings(linkSettings)
              .stations(stations);
      if (handler != null) {
        serverConfigBuilder.handler(handler);
      }
      if (callbackExecutor != null) {
        serverConfigBuilder.callbackExecutor(callbackExecutor);
      }
      ServerConfig serverConfig = serverConfigBuilder.build();

      // The builder is the sole 101 assembly point but holds no Ft12Frame<->octet wiring itself: it
      // delegates the full per-connection Ft12LinkLayer + framing assembly to Cs101Binding.
      Cs101Binding binding = new Cs101Binding(linkSettings, profile);

      // The outbound-queue bound and policy are passed as scalars rather than the ServerConfig
      // object so Cs101Binding stays free of an iec60870-application import.
      return new DefaultIec60870Server(
          transport,
          serverConfig,
          (connection, events, scheduler) ->
              binding.bindServer(
                  connection,
                  events,
                  scheduler,
                  serverConfig.maxOutboundQueue(),
                  serverConfig.eventQueuePolicy()));
    }
  }
}
