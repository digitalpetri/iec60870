# IEC 104 Examples

Runnable, documentation-first examples for the IEC 60870-5-104 library. Each class has a
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
mise exec -- mvn -q -pl iec104-examples -am compile
```

### With `exec:java`

```bash
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.RawAsduExample
```

`RawAsduExample` needs no peer. For the client/server pair, start the server in one shell and the
client in another:

```bash
# shell 1
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.ServerExample

# shell 2
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.ClientExample
```

`ServerExample` binds port 2404 and runs until interrupted (Ctrl+C); `ClientExample` connects to
`127.0.0.1:2404`, exercises the request operations, lingers 30 seconds for spontaneous updates,
then exits.

### With `java -cp`

After a build you can also run from the classpath directly:

```bash
CP=$(mise exec -- mvn -q -pl iec104-examples dependency:build-classpath \
    -Dmdep.outputFile=/dev/stdout -DincludeScope=runtime | tail -1)
java -cp "iec104-examples/target/classes:$CP" \
    com.digitalpetri.iec104.examples.ClientExample
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
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.TlsExample \
    -Dexec.args="" \
    -Diec104.tls.keystore=server.p12 -Diec104.tls.keystorePassword=changeit \
    -Diec104.tls.truststore=truststore.p12 -Diec104.tls.truststorePassword=changeit
```

## Test

`ExampleInteropTest` starts the `ServerExample` server on an ephemeral loopback port and runs the
client logic against it, asserting that interrogation, a command, and clock synchronization
succeed. Run it with:

```bash
mise exec -- mvn -q -pl iec104-examples -am test
```
