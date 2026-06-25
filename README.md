# IEC 104 for Java

IEC 104 is a Java implementation of IEC 60870-5-104, the TCP/IP profile for IEC 60870-5 telecontrol
communication. It is a Java 17, [Netty](https://netty.io/)-backed library with a two-layer API: a
faithful raw ASDU layer and a high-level
[controlling-station / controlled-station](docs/guide/reference/glossary.md) (master / slave) client
and server facade, with an escape hatch between them
(see the [architecture overview](docs/architecture/overview.md)).

This is a pre-release (`0.1.0-SNAPSHOT`): the API may change before `1.0`, and it is not yet
published to Maven Central (see [Dependency](#dependency)).

## Modules

- `iec60870-core`: core protocol model, serializers/codecs, and SPIs. Contains the raw ASDU layer
  (every standard TypeID with a co-located `Serde`), the `Session` and transport interfaces, and
  `ProtocolProfile`/`SessionSettings`. No Netty runtime types appear in its public API, and it holds
  no protocol session engine.
- `iec60870-cs104`: the genuinely-104 link/session layer. Holds `ApciSession` (which `implements
  Session`), the `Apdu`/`ControlField`/`UFunction` model with the `Apdu.Serde` codec, the
  `ApduFramer` `Apdu`↔`ByteBuf` bridge, `ApciSettings`, and `Cs104Binding` — the assembly point that
  wires an `ApciSession` to a core octet transport handle. Depends on `iec60870-core`.
- `iec60870-application`: the high-level layer with **no** Netty. Holds the `Iec60870Client` /
  `Iec60870Server` facades and the command/station/point/catalog model; depends on `iec60870-core`
  only and speaks purely in terms of `Asdu` + the `Session` SPI.
- `iec60870-transport-tcp`: Netty-backed TCP/TLS transport implementation, plus the user-facing
  `TcpIec104Client` / `TcpIec104Server` builders. The builders are the sole 104 assembly point: they
  construct the Netty transport, delegate the session/framing wiring to `Cs104Binding` (in `cs104`),
  and return the high-level facade.
- `iec60870-examples`: runnable client, server, raw-ASDU, and TLS examples.
- `iec60870-tests`: cross-module in-JVM client↔server integration tests (including TLS).
- `iec60870-interop`: interoperability tests that drive the library against `lib60870-C` peer images
  via Testcontainers. Tagged `@Tag("interop")` and excluded from the default build; run with
  `-Pinterop` and a running Docker daemon (see `iec60870-interop/README.md`).

## Dependency

Declare both modules. `iec60870-core` holds the protocol model and the SPIs (and stays free of Netty
runtime types); the `Iec60870Client` / `Iec60870Server` facades live in `iec60870-application`, which
`iec60870-transport-tcp` pulls in transitively. `iec60870-transport-tcp` adds the Netty-backed TCP/TLS
transport and the `TcpIec104Client` / `TcpIec104Server` builders you construct from. The transport
module depends on the core module transitively, but your code uses types from both, so declare both
directly.

**Pre-release.** This is `0.1.0-SNAPSHOT`; it is not yet published to Maven Central and the API may
change. Build and install it locally with `mise exec -- mvn install` (see [Building](#building)),
then depend on it as:

```xml
<dependency>
  <groupId>com.digitalpetri.iec60870</groupId>
  <artifactId>iec60870-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>com.digitalpetri.iec60870</groupId>
  <artifactId>iec60870-transport-tcp</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("com.digitalpetri.iec60870:iec60870-core:0.1.0-SNAPSHOT")
implementation("com.digitalpetri.iec60870:iec60870-transport-tcp:0.1.0-SNAPSHOT")
```

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

The sole exception is the [`iec60870-interop/docker/`](iec60870-interop/docker) subtree, which is
licensed under the [GNU General Public License v3.0 or later](iec60870-interop/docker/LICENSE.md). Those
files build custom C drivers that link the GPLv3 [lib60870-C](https://github.com/mz-automation/lib60870)
library into the interop test peer image. The library itself never links lib60870-C — it only speaks
to the resulting container over the network — so the EPL-licensed code and the GPL-licensed
`docker/` content stay cleanly separated.
