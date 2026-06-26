# Connect over serial (IEC 60870-5-101)

IEC 60870-5-101 is the serial sibling of 104: it carries the **identical** ASDU application layer —
the same `Iec60870Client` / `Iec60870Server` facade, the same `Asdu`, points, commands, and events —
over an [FT1.2](../reference/glossary.md) link layer instead of the 104
[APCI](../reference/glossary.md) over TCP. Everything you learned in
[Getting Started](../getting-started.md) and the other how-tos applies unchanged; only the builder
and two extra configuration records differ. You build a serial client with
`com.digitalpetri.iec60870.serial.SerialIec101Client`, a serial outstation with
`SerialIec101Server`, and — when the wire is TCP rather than an actual UART — the optional
101-over-TCP pair `com.digitalpetri.iec60870.tcp.TcpIec101Client` / `TcpIec101Server`.

The headline rule comes first: **a serial link has no on-wire parameter negotiation.** The
[link mode](../reference/link-layer.md) (balanced or unbalanced), the
[link address](../reference/glossary.md) and its octet width, the serial line parameters
(baud and the 8E1 character frame), and the [`ProtocolProfile`](../reference/glossary.md) field
widths are all a contract you must set identically on both stations. A mismatch is not reported by a
handshake; it surfaces as frames that never confirm or never decode. This page is the *how*; for the
*why* behind the FCB stop-and-wait flow control, the bring-up handshake, and the timers, see the
[Link layer reference](../reference/link-layer.md).

## Where the knobs live

Transport knobs — the serial port (or, for 101-over-TCP, the host/port/TLS) — live directly on the
builder. The two protocol records are the same ones the 104 builders take: you pass the field widths
via `.profile(ProtocolProfile)` and the FT1.2 link parameters via `.linkSettings(LinkSettings)`.
`LinkSettings` is built from `LinkSettings.balanced()` or `LinkSettings.unbalanced()`, each seeded
with the standard defaults, so you only set what differs.

| Knob category | Where it goes | Carried by |
|---|---|---|
| Serial port (port name, baud, data/parity/stop bits, RS-485, read/write timeouts) | builder method (`.serialPort`, `.baudRate`, `.parity`, `.rs485`, …) | the `SerialIec101*` builder |
| TCP transport (host, port, TLS, event loops) — 101-over-TCP only | builder method (`.host`, `.port`, `.tls`, …) | the `TcpIec101*` builder |
| Wire field widths | `.profile(...)` | `ProtocolProfile` |
| Link mode, address, acknowledgement style, timers | `.linkSettings(...)` | `LinkSettings` |

> **Profile defaults differ by transport.** The `SerialIec101*` builders default `.profile(...)` to
> `ProtocolProfile.iec104Default()` — `(2, 2, 3, 249)` — because the record is shared with 104. A
> real 101 peer usually uses narrower fields, so set a matching profile (commonly
> `new ProtocolProfile(1, 1, 2, 255)`). The `TcpIec101*` builders already default to that 101 profile
> `(1, 1, 2, 255)`. Either way, the profile is a [shared wire contract](#match-the-peer); match the
> peer exactly.

## Recipe: balanced client — connect and interrogate

Balanced transmission is the symmetric point-to-point case: two combined stations on a full-duplex
line, either of which may initiate. `connect()` opens the port and drives the FT1.2 link-reset
bring-up before it returns (because `startDataTransferOnConnect` defaults to `true`); after it
returns the link is up and you use the [same facade as 104](../getting-started.md).

```java
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.serial.SerialIec101Client;

// The SerialIec101* default profile is the 104 default; set the 101 widths the peer expects.
ProtocolProfile profile = new ProtocolProfile(1, 1, 2, 255);

// Balanced, this station's own link address is 1, one address octet (the balanced default).
LinkSettings link = LinkSettings.balanced().linkAddress(1).build();

try (Iec60870Client client =
    SerialIec101Client.builder()
        .serialPort("/dev/ttyUSB0")   // "COM3" on Windows
        .baudRate(9600)               // the 8E1 default character frame; match the peer
        .profile(profile)
        .linkSettings(link)
        .build()) {

  client.connect();   // opens the port and drives the FT1.2 link-reset bring-up

  InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
  for (InterrogationResult.PointEntry entry : snapshot.pointValues()) {
    System.out.printf("  %s = %s%n", entry.address(), entry.value().value());
  }
}
```

Subscribe to `client.events()` before connecting to receive spontaneous updates, exactly as in
[Handle events](handle-events.md); the event surface is identical to 104.

## Recipe: unbalanced master — poll several outstations

Unbalanced transmission is the master/secondary case: one primary station owns the bus and polls one
or more secondaries, which never initiate. Build it with `LinkSettings.unbalanced()` and name the
secondary link addresses to poll with `.slaveAddresses(...)` and the cadence with `.pollInterval(...)`.
Each secondary buffers its spontaneous (class-1) and cyclic (class-2) data between polls; the master
drains it round-robin on the configured interval.

```java
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.serial.SerialIec101Client;
import java.time.Duration;
import java.util.List;

ProtocolProfile profile = new ProtocolProfile(1, 1, 2, 255);

// Unbalanced master: poll secondaries at link addresses 1, 2, and 3 once per second.
LinkSettings link =
    LinkSettings.unbalanced()
        .slaveAddresses(List.of(1, 2, 3))
        .pollInterval(Duration.ofMillis(1000))   // the default cadence; shown for clarity
        .build();

try (Iec60870Client client =
    SerialIec101Client.builder()
        .serialPort("/dev/ttyUSB0")
        .baudRate(9600)
        .profile(profile)
        .linkSettings(link)
        .build()) {

  client.connect();   // brings up each secondary, then polls on the cadence
  // interrogate(...) / commands() target a secondary by its CommonAddress, as on 104.
}
```

Unbalanced mode always carries a link address (`linkAddressLength` is `1` or `2`, never `0`), and the
`broadcastAddress` (`255` for one octet, `65535` for two) is the all-secondaries address used for
send/no-reply messages. Both default correctly from `LinkSettings.unbalanced()`.

## Recipe: serial outstation

`SerialIec101Server` is the serial peer of `TcpIec104Server`. A serial line joins exactly two
stations, so there is no bind address, port, TLS, or connection cap — just the port and the link
settings. Build the `Station`s and `ServerHandler` exactly as for a 104 server (see
[Host a server](host-a-server.md)); only the builder changes.

```java
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.serial.SerialIec101Server;

ProtocolProfile profile = new ProtocolProfile(1, 1, 2, 255);

// Balanced point-to-point: the two stations carry distinct link addresses (client 1, server 2).
LinkSettings link = LinkSettings.balanced().linkAddress(2).build();

try (Iec60870Server server =
    SerialIec101Server.builder()
        .serialPort("/dev/ttyUSB0")
        .baudRate(9600)
        .profile(profile)
        .linkSettings(link)
        .addStation(station)   // see "Host a server" for building stations + points
        .handler(handler)      // see "Host a server" for the ServerHandler
        .build()) {

  server.start();
  server.publish(measurement, PointValue.scaled((short) 4242), Cause.SPONTANEOUS);
}
```

The `station`, `handler`, and `measurement` locals are built exactly as on 104 — see
[Host a server](host-a-server.md) for the full station-and-point setup and the command-handling
`ServerHandler`.

## Recipe: RS-485 multidrop

On a two-wire RS-485 bus the transmitter and receiver share one differential pair, so the driver
must turn the line around every transmission. Configure that turnaround with
`com.digitalpetri.iec60870.transport.serial.Rs485Options` and attach it via `.rs485(...)`; attaching
any instance enables RS-485 mode on the port. An RS-485 bus is typically an unbalanced multidrop with
the master polling each secondary.

```java
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.transport.serial.Rs485Options;
import com.digitalpetri.iec60870.serial.SerialIec101Client;
import java.util.List;

Rs485Options rs485 =
    Rs485Options.builder()
        .rtsActiveHigh(true)          // RTS asserted high while transmitting
        .delayAfterSendMicros(500)    // hold the line before releasing transmit mode
        .build();

try (Iec60870Client client =
    SerialIec101Client.builder()
        .serialPort("/dev/ttyUSB0")
        .baudRate(19200)
        .linkSettings(LinkSettings.unbalanced().slaveAddresses(List.of(1, 2)).build())
        .rs485(rs485)
        .build()) {
  client.connect();
}
```

> **Footgun: RS-485 turnaround is platform-dependent.** Only *enabling* RS-485 mode is portable.
> Every turnaround parameter beyond that switch — `rtsActiveHigh`, `enableTermination`, `rxDuringTx`,
> `delayBeforeSendMicros`, `delayAfterSendMicros` — is effective **only on Linux**, where the driver
> applies them through the kernel `SER_RS485` ioctl. On Windows RS-485 mode maps to basic auto-RTS
> only and these fields are silently ignored, so reliable high-speed RS-485 there needs an adapter
> with auto-direction hardware; on macOS RS-485 mode is generally unsupported by the OS drivers.
> Never toggle RTS from Java around a write to fake turnaround — the JVM cannot time the release to
> the final stop bit, so it clips the frame tail or collides with the peer's reply. Let the driver
> own the timing.

## Recipe: 101 over TCP

When the link is TCP rather than a physical UART — a serial-to-Ethernet gateway, a simulator, a
remote terminal server — the FT1.2 link layer can run directly over a TCP (or TLS) connection. Use
`TcpIec101Client` / `TcpIec101Server`: they reuse the same Netty transport as the 104 builders but
frame FT1.2 on the wire instead of the 104 APCI. The protocol surface is identical to the serial
builders; only the transport knobs change to host/port/TLS.

```java
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.tcp.TcpIec101Client;

try (Iec60870Client client =
    TcpIec101Client.builder()
        .host("127.0.0.1")
        .port(2404)   // 101-over-TCP has no registered port; match the peer
        .linkSettings(LinkSettings.balanced().linkAddress(1).build())
        .build()) {

  client.connect();   // TCP connect, then the FT1.2 link-reset bring-up
  InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
}
```

The `TcpIec101*` builders default `.profile(...)` to the 101 profile `(1, 1, 2, 255)` (not the 104
default), and the default `.port(...)` is `2404` only as a convenience — 101-over-TCP has no
registered port, so set it to match the peer. Secure the connection with `.tls(TlsOptions)` exactly
as in [Secure with TLS](secure-with-tls.md); the outstation builder
`TcpIec101Server` mirrors `TcpIec104Server` (bind address, port, `maxConnections`, TLS).

## Match the peer

A 101 link interoperates only when both stations agree on every item below. None of it is negotiated
on the wire, so set both ends yourself.

- **Link mode** — both stations must run the same machine: balanced on both ends, or one unbalanced
  master against unbalanced secondaries. The two are not interoperable.
- **Link address and width** — `linkAddressLength` must match (`0`/`1`/`2`; `0` is balanced-only),
  and the addresses must be consistent: in balanced mode the two ends carry distinct addresses; in
  unbalanced mode each secondary's `linkAddress` must appear in the master's `slaveAddresses`.
- **Serial line parameters** — baud rate and the character frame (the standard 8E1: 8 data bits,
  even parity, 1 stop bit, the `SerialIec101*` defaults) must match. FT1.2 requires even parity.
- **Field widths** — the `ProtocolProfile` (COT length, common-address width, IOA width, max ASDU
  length) is the same shared wire contract as on 104. Match it; see
  [Tune the APCI session](tune-apci.md) for the field-width decision table (the addressing half
  applies identically to 101).

For the FT1.2 timers (`confirmTimeout`, `repeatTimeout`, `linkStateTimeout`), the FCB stop-and-wait
flow control, and the full balanced-vs-unbalanced semantics, see the
[Link layer reference](../reference/link-layer.md).

## See also

- [Link layer reference](../reference/link-layer.md) — FT1.2 defaults, FCB stop-and-wait vs. the
  104 `k`/`w` window, balanced vs. unbalanced, and the link timers.
- [Tune the APCI session](tune-apci.md) — the 104 transport analog, and the shared `ProtocolProfile`
  field-width decision table.
- [Getting Started](../getting-started.md) — the end-to-end happy path; the facade above the link is
  identical for 101 and 104.
- [Host a server](host-a-server.md) — building the stations, points, and `ServerHandler` the
  outstation recipe elides.
- [Connect & interrogate](connect-and-interrogate.md) and [Send commands](send-commands.md) —
  exercising a 101 client; the operations are unchanged from 104.
- [Secure with TLS](secure-with-tls.md) — the `.tls(...)` knob for the 101-over-TCP builders.
- [Glossary](../reference/glossary.md) — FT1.2, link address, FCB/FCV, balanced/unbalanced, and the
  rest of the vocabulary.
- [User Guide index](../README.md) — the rest of the guide.
