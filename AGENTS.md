# IEC 104

IEC 104 is a Maven / Java 17 implementation of IEC 60870-5-104.

## Tech Stack

- Java 17, managed through `mise` using `.mise.toml`.
- Maven multi-module build; dependency and plugin versions are centralized in the root POM.
- Netty for the TCP/TLS transport module; `iec104-core` depends only on `netty-buffer` (`ByteBuf` is
  the codec boundary), not on Netty runtime types.
- JOOU for unsigned integer types in the protocol model.
- SLF4J for logging (no binding is shipped to consumers).
- JUnit 5 for tests; Testcontainers for the lib60870-C interop tests.
- JSpecify for nullness annotations.
- Spotless with google-java-format enforces code style; the check runs as part of `mvn verify`.

## Project Layout

- `pom.xml` is the parent Maven project (group `com.digitalpetri.iec104`, artifact `iec104-parent`).
- `iec104-core/` owns the protocol model and the Netty-runtime-free public API: the raw ASDU layer
  (every standard TypeID with a co-located `Serde`), the APCI session engine, the transport
  interfaces, and the high-level `Iec104Client` / `Iec104Server` facades plus the point/catalog model.
- `iec104-transport-tcp/` owns the Netty-backed TCP/TLS transport and the user-facing
  `TcpIec104Client` / `TcpIec104Server` builders.
- `iec104-examples/` holds runnable client, server, raw-ASDU, and TLS examples.
- `iec104-tests/` owns cross-module in-JVM client↔server integration tests (including TLS).
- `iec104-interop/` holds interoperability tests that drive the library against `lib60870-C` peer
  images via Testcontainers; tagged `@Tag("interop")` and excluded from the default build (see
  Common Commands). Its `docker/` subtree is GPLv3; the rest of the project is EPL 2.0.

Modules are declared in the parent POM in build order: `iec104-core`, `iec104-transport-tcp`,
`iec104-examples`, `iec104-tests`, `iec104-interop`. Source lives under `com.digitalpetri.iec104`; in
`iec104-core` the main packages are `asdu` (with `asdu.object`, `asdu.element`, `asdu.time`), `apci`,
`codec`, `address`, `transport`, `client`, `server`, `point`, and `catalog`.

Keep new modules under the parent build and centralize dependency/plugin versions in the root POM.

### Architecture invariant

Keep the `iec104-core` public API free of Netty *runtime* types (channels, event loops, TLS
engines) — those belong to `iec104-transport-tcp`. Core deliberately depends on `netty-buffer`, so
`ByteBuf` is the one Netty type that appears at the raw ASDU `Serde`/codec boundary. This
core-vs-transport split is the central design constraint; see `docs/architecture/`.

## Common Commands

- Install pinned tools: `mise install`
- Format the codebase: `mise exec -- mvn spotless:apply`
- Verify the build: `mise exec -- mvn clean verify`
- Compile without tests: `mise exec -- mvn clean compile`
- Download dependency sources: `mise exec -- mvn -q package -DskipTests -Pdownload-external-src`
- Run a specific test class: `mise exec -- mvn -q -pl <module> test -Dtest=ClassName`
  (see `docs/running-tests.md` for module-targeting patterns)
- Run the lib60870-C interop tests (Docker required, excluded by default):
  `TESTCONTAINERS_RYUK_DISABLED=true mise exec -- mvn -pl iec104-interop -am -Pinterop test`
  (see `iec104-interop/README.md` for details)

## Additional References

Before writing code or Javadoc, read the applicable references below. Treat them as required
project instructions, not optional background material.

- Java coding conventions: `docs/java-coding-conventions.md`
- Documentation guidelines: `docs/documentation-guidelines.md`
- Running tests / module-targeting flags: `docs/running-tests.md`
- Interop test setup (Docker/Testcontainers): `iec104-interop/README.md`

### Architecture Documentation

`docs/architecture/` documents the system as built; start with `docs/architecture/README.md`, which
indexes the rest. Read the relevant document before changing behavior in that area:

- `overview.md` — what IEC 104 is, the two-layer API philosophy, and a component map.
- `modules-and-dependencies.md` — the core-vs-transport split and the rules that keep Netty runtime
  types out of core.
- `two-layer-api.md` — the raw layer (`Asdu`, `Cause`, `InformationObject` records, `Serde` codecs)
  vs. the high-level layer (`Iec104Client` / `Iec104Server`, station/point model, commands, events).
- `protocol-coverage.md` — the ASDU TypeID coverage matrix and what is intentionally out of scope.
- `apci-and-timers.md` — STARTDT/STOPDT/TESTFR, I/S/U frames, the `k`/`w` window, `t0`–`t3` timers,
  and how `ApciSession` implements them.
- `buffers-and-threading.md` — `ByteBuf` ownership/release rules and the callback-serialization model.
- `tls-and-configuration.md` — `TlsOptions`, handshake gating, and the `ProtocolProfile` /
  `ApciSettings` configuration types.
- `errors-and-extensibility.md` — typed exceptions vs. result objects, and the raw send/receive hooks for unmodeled TypeIDs.

### Dependency Source Code

To examine dependency source code, check the `external/src` directory at the project root. This
directory contains unpacked source files from all dependencies, organized by package structure for
easy browsing and searching.

#### Setup

If the directory doesn't exist or content is missing, run this command from the project root to
download and unpack all dependency sources:

```bash
mise exec -- mvn -q package -DskipTests -Pdownload-external-src
```

This creates the `external/src` directory with sources from all dependencies in a single top-level
location.

## Verification

- Run `mise exec -- mvn spotless:apply` after code changes and before final validation. Include any
  formatting changes it makes.
- Run `mise exec -- mvn clean verify` before handing off normal code changes; this also runs the
  Spotless check.
- While iterating, prefer targeted checks such as
  `mise exec -- mvn -q -pl iec104-core test -Dtest=ClassName` or
  `mise exec -- mvn -q -pl iec104-transport-tcp -am test -Dtest=ClassName`.
- For documentation-only changes, run the relevant lightweight check when one exists and clearly
  state if the full Maven build was skipped.
- If a verification command cannot be run, report the command, the reason it was skipped or failed,
  and any follow-up needed.
