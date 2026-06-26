# IEC 60870 for Java

This project is a Java implementation of IEC 60870-5, covering both the CS 104 (networked, TCP/IP) and
CS 101 (serial, plus 101-over-TCP) companion standards for telecontrol communication. It is a Java 17, [Netty](https://netty.io/)-backed library with a two-layer API: a
faithful raw ASDU layer and a high-level
[controlling-station / controlled-station](docs/guide/reference/glossary.md) (master / slave) client
and server facade, with an escape hatch between them
(see the [architecture overview](docs/architecture/overview.md)).

This is a pre-release (`0.1.0-SNAPSHOT`): the API may change before `1.0`, and it is not yet
published to Maven Central (see [Dependency](#dependency)).

## Usage

A controlled station (server) hosts points; a controlling station (client) connects to it,
interrogates the current image, and issues commands. The two snippets below are mirror images of one
system: the server hosts a single point at
[common address](docs/guide/reference/glossary.md) `1` /
[information object address](docs/guide/reference/glossary.md) `100`, and the client interrogates
that station and commands that point.

```java
// Controlled station (server)
Station station = Station.builder(CommonAddress.of(1))
    .point(PointDefinition.of(
        PointAddress.of(1, 100), PointType.SINGLE_POINT,
        PointValue.single(false),
        PointCapability.REPORTED, PointCapability.COMMANDABLE))
    .group(1, PointAddress.of(1, 100))
    .build();

// Accept the single command on (1, 100) and write the image, so the master
// sees the new value as return information; reject anything else.
ServerHandler handler = new ServerHandler() {
    @Override
    public CommandDecision onCommand(ServerContext context, CommandRequest request) {
        if (request.target().equals(PointAddress.of(1, 100))
            && request.commandObject() instanceof SingleCommand command) {
            return CommandDecision.acceptAndUpdate(PointValue.single(command.on()));
        }
        return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
    }
};

try (Iec60870Server server = TcpIec104Server.builder()
        .bindAddress("0.0.0.0").port(2404)
        .addStation(station)
        .handler(handler)
        .build()) {

    server.start();
    // Push a spontaneous update to started connections.
    server.publish(PointAddress.of(1, 100), PointValue.single(true), Cause.SPONTANEOUS);
}
```

```java
// Controlling station (master)
try (Iec60870Client client = TcpIec104Client.builder()
        .host("127.0.0.1").port(2404)
        .startDataTransferOnConnect(true)
        .build()) {

    client.connect();
    InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
    CommandResult result = client.commands()
        .single(PointAddress.of(1, 100), true);
}
```

`interrogate(...)` returns a snapshot of the station image (`snapshot.pointValues()`); `single(...)`
issues a single command and returns a `CommandResult` whose `positive()` reports whether the station
confirmed it. Spontaneous updates and connection lifecycle changes arrive asynchronously via
`client.events()`. The snippets omit imports for brevity; the types live under
`com.digitalpetri.iec60870.*`.

Both snippets are runnable end to end in
[Getting Started](docs/guide/getting-started.md); the full versions live in `ServerExample` and
`ClientExample` (see [`iec60870-examples/`](iec60870-examples/README.md)).

### Serial (CS101)

The same `Iec60870Client` / `Iec60870Server` facades, station/point model, and commands also drive
an IEC 60870-5-101 serial link; only the builder and the underlying FT1.2 link layer differ. Build a
controlling station over a balanced point-to-point serial link with `SerialIec101Client`:

```java
// Controlling station (master) over a balanced serial link
try (Iec60870Client client = SerialIec101Client.builder()
        .serialPort("/dev/ttyUSB0")
        .baudRate(9600)
        .linkSettings(LinkSettings.balanced().linkAddress(1).build())
        .build()) {

    client.connect();
    InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
    CommandResult result = client.commands()
        .single(PointAddress.of(1, 100), true);
}
```

`connect()` opens the serial port and, with `startDataTransferOnConnect` enabled (the default),
drives the FT1.2 balanced link-reset bring-up before completing. An unbalanced (master / slave)
multi-drop master uses `LinkSettings.unbalanced()` with a `PollConfig` (its `slaveAddresses` and
`pollInterval`) and the matching `SerialIec101Server` secondary; the optional `TcpIec101Client` /
`TcpIec101Server` builders (in `iec60870-tcp`) carry the same FT1.2 link layer over a
TCP/TLS connection.

## Dependency

Depend on the assembly module for your transport — `iec60870-tcp` for IEC 60870-5-104 (and the
optional 101-over-TCP path) or `iec60870-serial` for IEC 60870-5-101 over a serial link. Each
assembly module transitively pulls in everything you need: the core protocol model and SPIs
(`iec60870-core`), the link layer(s), the octet transport, and the `Iec60870Client` /
`Iec60870Server` application facade. It is the only coordinate you declare; the types you use from
`com.digitalpetri.iec60870.*` arrive transitively.

**Pre-release.** This is `0.1.0-SNAPSHOT`; it is not yet published to Maven Central and the API may
change. Build and install it locally with `mise exec -- mvn install` (see [Building](#building)),
then depend on the assembly module for your transport:

```xml
<!-- TCP: IEC 60870-5-104, plus the optional 101-over-TCP builders -->
<dependency>
  <groupId>com.digitalpetri.iec60870</groupId>
  <artifactId>iec60870-tcp</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- or, for IEC 60870-5-101 over a serial link -->
<dependency>
  <groupId>com.digitalpetri.iec60870</groupId>
  <artifactId>iec60870-serial</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
// TCP: IEC 60870-5-104, plus the optional 101-over-TCP builders
implementation("com.digitalpetri.iec60870:iec60870-tcp:0.1.0-SNAPSHOT")

// or, for IEC 60870-5-101 over a serial link
implementation("com.digitalpetri.iec60870:iec60870-serial:0.1.0-SNAPSHOT")
```

## Modules

Most users depend on a single assembly module — `iec60870-tcp` or `iec60870-serial` — and get the
rest transitively. The full breakdown, including the core-vs-transport split and the assembly-point
rules, lives in
[`docs/architecture/modules-and-dependencies.md`](docs/architecture/modules-and-dependencies.md).

**Depend on one of these**

- `iec60870-tcp` — user-facing TCP builders for IEC 60870-5-104 (and the optional 101-over-TCP path).
- `iec60870-serial` — user-facing serial builders for IEC 60870-5-101.

**Protocol core** (pulled in transitively)

- `iec60870-core` — the raw ASDU model, codecs/`Serde`s, and the `Session`/transport SPIs; no Netty
  runtime types in its public API and no session engine.
- `iec60870-cs104` — the 104 link/session layer (`ApciSession`, APDU framing).
- `iec60870-cs101` — the 101 link/session layer (FT1.2 link layer).
- `iec60870-application` — the `Iec60870Client` / `Iec60870Server` facades and the
  command/station/point/catalog model, with **no** Netty.

**Octet transports**

- `iec60870-transport-tcp` — the Netty-backed TCP/TLS transport.
- `iec60870-transport-serial` — the jSerialComm-backed serial transport.

**Examples & tests**

- `iec60870-examples` — runnable client, server, raw-ASDU, and TLS examples.
- `iec60870-test-integration` — cross-module in-JVM client↔server integration tests (including TLS).
- `iec60870-test-interop` — interoperability tests against `lib60870-C` peer images via
  Testcontainers; tagged `@Tag("interop")` and excluded from the default build (run with `-Pinterop`
  and a running Docker daemon — see `iec60870-test-interop/README.md`).

## Documentation

Start with the **[User Guide](docs/guide/README.md)** — a task-oriented guide organized as a
tutorial, how-to recipes, and reference pages. The
**[architecture docs](docs/architecture/README.md)** describe the system as built, for integrators
and extenders.

**Get started**

- [Getting Started](docs/guide/getting-started.md) — install, mental model, hello-server,
  hello-client, run it.

**How-to guides**

- [Connect & interrogate](docs/guide/how-to/connect-and-interrogate.md) — connect a client and run
  general / counter interrogation.
- [Send commands](docs/guide/how-to/send-commands.md) — single / double / regulating / setpoint;
  direct execute vs. select-before-operate.
- [Host a server](docs/guide/how-to/host-a-server.md) — stations, points, catalog, command handling,
  spontaneous transmission.
- [Handle events](docs/guide/how-to/handle-events.md) — the listener/handler surface and the
  threading rules.
- [Secure with TLS](docs/guide/how-to/secure-with-tls.md) — `TlsOptions`, keystores, hostname
  verification, handshake gating.
- [Tune the APCI session](docs/guide/how-to/tune-apci.md) — `k`/`w` window, `t0`–`t3` timers, and
  addressing field sizes.
- [Work with raw ASDUs](docs/guide/how-to/work-with-raw-asdus.md) — drop to the raw layer for
  unmodeled TypeIDs via the send/receive hooks.

**Reference**

- [Coverage matrix](docs/guide/reference/coverage-matrix.md) — supported ASDU TypeIDs.
- [Glossary](docs/guide/reference/glossary.md) — IEC 104 vocabulary mapped to our Java types.
- [Choosing a point type](docs/guide/reference/choosing-a-point-type.md) — map real-world signals to
  a TypeID and model record.
- [Timers & window](docs/guide/reference/timers-and-window.md) — `t0`–`t3` defaults and `k`/`w`
  semantics.
- [Error model](docs/guide/reference/errors.md) — typed exceptions vs. result objects.

**Architecture** — see [`docs/architecture/`](docs/architecture/README.md): the two-layer API, the
core-vs-transport split, the protocol coverage matrix, the APCI lifecycle and timers, buffer
ownership and threading, TLS and configuration, and the error model and extensibility points.

## Requirements

The repository pins its Java and Maven toolchain with `mise`:

```shell
mise install
```

After installation, run Maven through `mise exec` so the pinned Java 17 and Maven versions are used.

## Building

Compile the project:

```shell
mise exec -- mvn clean compile
```

Run the full build:

```shell
mise exec -- mvn clean verify
```

Apply formatting:

```shell
mise exec -- mvn spotless:apply
```

Run tests for a single module:

```shell
mise exec -- mvn -q -pl iec60870-core test
```

Run a specific test class:

```shell
mise exec -- mvn -q -pl <module> test -Dtest=ClassName
```

Download dependency sources for local browsing:

```shell
mise exec -- mvn -q package -DskipTests -Pdownload-external-src
```

Dependency sources are unpacked under `external/src`.

## Development Notes

Build configuration is centralized in the parent `pom.xml`. Keep core protocol APIs free of Netty
runtime types unless a future design explicitly chooses a Netty-buffer-facing codec boundary.

## License

This project is licensed under the [Eclipse Public License 2.0](LICENSE.md).

The sole exception is the [`iec60870-test-interop/docker/`](iec60870-test-interop/docker) subtree, which is
licensed under the [GNU General Public License v3.0 or later](iec60870-test-interop/docker/LICENSE.md). Those
files build custom C drivers that link the GPLv3 [lib60870-C](https://github.com/mz-automation/lib60870)
library into the interop test peer image. The library itself never links lib60870-C — it only speaks
to the resulting container over the network — so the EPL-licensed code and the GPL-licensed
`docker/` content stay cleanly separated.
