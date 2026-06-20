# IEC 104 for Java

IEC 104 is a Java implementation of IEC 60870-5-104, the TCP/IP profile for IEC
60870-5 telecontrol communication. The project is currently bootstrapped as a Java 17 Maven
multi-module build, with the protocol model and application APIs kept separate from Netty transport
details.

## Modules

- `iec104-core`: core protocol model, serializers/codecs, client/server APIs, and transport
  interfaces.
- `iec104-transport-tcp`: Netty-backed TCP/TLS transport implementation.
- `iec104-tests`: cross-module integration tests.

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
