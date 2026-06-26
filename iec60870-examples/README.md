# IEC 60870 Examples

Runnable, documentation-first examples for the IEC 60870 library — IEC 60870-5-104 over TCP/TLS,
plus IEC 60870-5-101 (FT1.2) over a serial line or over TCP. Each class has a
`public static void main` and is meant to be read as much as run.

The [Getting Started tutorial](../docs/guide/getting-started.md) drives `ServerExample` and
`ClientExample` as its end-to-end happy path, and each how-to guide links to the example that
demonstrates it — see [Where these fit in the guide](#where-these-fit-in-the-guide) below.

| Class | What it shows | Guide |
| --- | --- | --- |
| `ServerExample` | A controlled station: hosts one monitor point of every logical point type plus a commandable point; accepts a command and updates the point image; periodically republishes a fresh value for every point. | [Host a server](../docs/guide/how-to/host-a-server.md) |
| `ClientExample` | A controlling station: connects, STARTDT, subscribes to events, runs a general interrogation, sends a single command (direct execute), synchronizes the clock, then lingers for spontaneous updates. | [Connect & interrogate](../docs/guide/how-to/connect-and-interrogate.md) |
| `RawAsduExample` | The protocol layer without the facade: builds an `Asdu` by hand, encodes it to bytes, prints the hex, and decodes it back. | [Work with raw ASDUs](../docs/guide/how-to/work-with-raw-asdus.md) |
| `TlsExample` | The client/server pair secured with TLS, configured from a keystore/truststore supplied via system properties. | [Secure with TLS](../docs/guide/how-to/secure-with-tls.md) |
| `SerialServerExample` | A controlled station over a serial line: hosts a station of monitor and commandable points with `SerialIec101Server`, accepts a command and updates the point image, and periodically republishes fresh values over the balanced FT1.2 link — the 101 analog of `ServerExample`. Prints connection guidance and exits when no serial port is supplied. | [Connect over serial](../docs/guide/how-to/connect-over-serial.md) |
| `SerialClientExample` | A controlling station over a serial line: connects a balanced IEC 60870-5-101 client built with `SerialIec101Client`, runs a general interrogation, sends a single command (direct execute), synchronizes the clock, then lingers for spontaneous updates; also shows an unbalanced-master (`LinkSettings.unbalanced()`) polling configuration — the 101 analog of `ClientExample`. Prints connection guidance and exits when no serial port is supplied. | [Connect over serial](../docs/guide/how-to/connect-over-serial.md) |
| `Tcp101Example` | The IEC 60870-5-101 (FT1.2) link layer carried over TCP: starts a `TcpIec101Server` and `TcpIec101Client` in the same process on a loopback port, then interrogates, sends a command, and observes a spontaneous update over the 101-over-TCP stack — no serial port or Docker required. | [Connect over serial](../docs/guide/how-to/connect-over-serial.md) |

## Where these fit in the guide

The [Getting Started](../docs/guide/getting-started.md) tutorial walks through `ServerExample` and
then `ClientExample` as a single happy path; read it first if you want the narrated version of these
two `main` methods. Each how-to guide then anchors on one example, and most examples carry more than
the one link the table above had room for:

- `ServerExample` → [Host a server](../docs/guide/how-to/host-a-server.md) (stations, points,
  catalog, command handling, spontaneous/periodic transmission) and
  [Handle events](../docs/guide/how-to/handle-events.md) (the `ServerHandler` callback surface).
- `ClientExample` → [Connect & interrogate](../docs/guide/how-to/connect-and-interrogate.md),
  [Send commands](../docs/guide/how-to/send-commands.md), and
  [Handle events](../docs/guide/how-to/handle-events.md) (the `Flow.Subscriber` event stream).
- `RawAsduExample` → [Work with raw ASDUs](../docs/guide/how-to/work-with-raw-asdus.md).
- `TlsExample` → [Secure with TLS](../docs/guide/how-to/secure-with-tls.md).
- `SerialServerExample`, `SerialClientExample`, and `Tcp101Example` →
  [Connect over serial](../docs/guide/how-to/connect-over-serial.md) (the 101 recipe: the
  `SerialIec101Client` / `SerialIec101Server` / `TcpIec101Client` / `TcpIec101Server` builders, the
  `LinkSettings` balanced and unbalanced modes, and the shared `ProtocolProfile` field widths), with
  the [Link layer reference](../docs/guide/reference/link-layer.md) for the FT1.2 flow control,
  bring-up, and timers. The protocol methods — interrogation, commands, and the event stream — are
  the same as the 104 how-tos above
  ([Connect & interrogate](../docs/guide/how-to/connect-and-interrogate.md),
  [Send commands](../docs/guide/how-to/send-commands.md),
  [Host a server](../docs/guide/how-to/host-a-server.md)).

For everything else, see the [user guide](../docs/guide/README.md) for the full table of contents —
the reference pages (coverage matrix, glossary, choosing a point type, timers & window, error model)
and the remaining how-tos such as [Tune the APCI session](../docs/guide/how-to/tune-apci.md).

A future addition could include a small "dump the station/point tree" utility — a client that
connects, runs a general interrogation, and prints every station's points and their current values —
as a teaching device for the
[point model](../docs/guide/reference/choosing-a-point-type.md). It is not part of this module yet.

## Running

The examples module is not published; build the reactor first so the dependencies are available:

```bash
mise exec -- mvn -q -pl iec60870-examples -am compile
```

### With `exec:java`

```bash
mise exec -- mvn -q -pl iec60870-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec60870.examples.RawAsduExample
```

`RawAsduExample` needs no peer. For the client/server pair, start the server in one shell and the
client in another:

```bash
# shell 1
mise exec -- mvn -q -pl iec60870-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec60870.examples.ServerExample

# shell 2
mise exec -- mvn -q -pl iec60870-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec60870.examples.ClientExample
```

`ServerExample` binds port 2404 and runs until interrupted (Ctrl+C); `ClientExample` connects to
`127.0.0.1:2404`, exercises the request operations, lingers 30 seconds for spontaneous updates,
then exits.

### With `java -cp`

After a build you can also run from the classpath directly:

```bash
CP=$(mise exec -- mvn -q -pl iec60870-examples dependency:build-classpath \
    -Dmdep.outputFile=/dev/stdout -DincludeScope=runtime | tail -1)
java -cp "iec60870-examples/target/classes:$CP" \
    com.digitalpetri.iec60870.examples.ClientExample
```

### TLS example

`TlsExample` reads its key and trust material from system properties and prints instructions if
they are missing. Generate a self-signed PKCS#12 keystore and a matching truststore with `keytool`:

```bash
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
    -dname "CN=localhost" -ext "san=dns:localhost,ip:127.0.0.1" \
    -keystore server.p12 -storetype PKCS12 -storepass changeit -validity 365
keytool -exportcert -alias server -keystore server.p12 -storepass changeit -file server.crt
keytool -importcert -noprompt -alias server -file server.crt \
    -keystore truststore.p12 -storetype PKCS12 -storepass changeit
```

Then run it (it starts a TLS server and client in-process and interrogates over the secured link):

```bash
mise exec -- mvn -q -pl iec60870-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec60870.examples.TlsExample \
    -Dexec.args="" \
    -Diec104.tls.keystore=server.p12 -Diec104.tls.keystorePassword=changeit \
    -Diec104.tls.truststore=truststore.p12 -Diec104.tls.truststorePassword=changeit
```

### Serial and 101-over-TCP examples

`SerialClientExample` and `SerialServerExample` build the IEC 60870-5-101 client and server over a
serial line with `SerialIec101Client` and `SerialIec101Server`. Both require a real serial port to
actually connect; each takes an optional `[portName [baudRate]]` argument pair and defaults to
`/dev/ttyUSB0` at 9600 baud. With no serial device attached (the usual case in continuous
integration) opening the port fails, so each prints a friendly message naming the port it tried and
exits gracefully without throwing. Run them the same way as the other examples and follow the
guidance they print:

```bash
mise exec -- mvn -q -pl iec60870-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec60870.examples.SerialServerExample

mise exec -- mvn -q -pl iec60870-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec60870.examples.SerialClientExample
```

With a real port pair (two USB serial adapters cross-wired, or a virtual null-modem pair), start
`SerialServerExample` on one port and `SerialClientExample` on the other; the client drives the
FT1.2 link reset, interrogates, sends a command, and synchronizes the clock over the balanced link.

`Tcp101Example` needs no serial port: it carries the same FT1.2 link layer over TCP with
`TcpIec101Server` and `TcpIec101Client`, starting both in-process on a loopback port and
interrogating over the 101-over-TCP stack, then exits.

```bash
mise exec -- mvn -q -pl iec60870-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec60870.examples.Tcp101Example
```

## Test

`ExampleInteropTest` starts the `ServerExample` server on an ephemeral loopback port and runs the
client logic against it, asserting that interrogation, a command, and clock synchronization
succeed. Run it with:

```bash
mise exec -- mvn -q -pl iec60870-examples -am test
```
