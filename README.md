# IEC 104 for Java

IEC 104 is a Java implementation of IEC 60870-5-104, the TCP/IP profile for IEC
60870-5 telecontrol communication. The project is currently bootstrapped as a Java 17 Maven
multi-module build, with the protocol model and application APIs kept separate from Netty transport
details.

## Modules

- `iec104-core`: core protocol model, serializers/codecs, client/server APIs, and transport
  interfaces. Contains the raw ASDU layer (every standard TypeID with a co-located `Serde`), the APCI
  session engine, and the high-level `Iec104Client`/`Iec104Server` facades. No Netty runtime types
  appear in its public API.
- `iec104-transport-tcp`: Netty-backed TCP/TLS transport implementation, plus the user-facing
  `TcpIec104Client` / `TcpIec104Server` builders.
- `iec104-examples`: runnable client, server, raw-ASDU, and TLS examples.
- `iec104-tests`: cross-module in-JVM client↔server integration tests (including TLS).
- `iec104-interop`: interoperability tests that drive the library against `lib60870-C` peer images
  via Testcontainers. Tagged `@Tag("interop")` and excluded from the default build; run with
  `-Pinterop` and a running Docker daemon (see `iec104-interop/README.md`).

## Documentation

Architecture and design documentation lives under [`docs/architecture/`](docs/architecture/): the
two-layer API, the core-vs-transport split, the protocol coverage matrix, the APCI lifecycle and
timers, buffer ownership and threading, TLS and configuration, and the error model and extensibility
points.

## Usage

```java
// Controlling station (master)
try (Iec104Client client = TcpIec104Client.builder()
        .host("127.0.0.1").port(2404)
        .startDataTransferOnConnect(true)
        .build()) {

    client.connect();
    InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
    CommandResult result = client.commands()
        .single(new PointAddress(CommonAddress.of(1), InformationObjectAddress.of(5000)), true);
}
```

## Requirements

The repository pins its Java and Maven toolchain with `mise`:

```shell
mise install
```

After installation, run Maven through `mise exec` so the pinned Java 17 and Maven versions are used.

## Getting Started

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
mise exec -- mvn -q -pl iec104-core test
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
