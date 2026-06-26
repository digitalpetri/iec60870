# IEC 60870

This project is a Maven / Java 17 implementation of IEC 60870-5, covering both the CS 104
(networked, TCP/IP) and CS 101 (serial, plus 101-over-TCP) companion standards.

## Tech Stack

- Java 17, managed through `mise` using `.mise.toml`.
- Maven multi-module build; dependency and plugin versions are centralized in the root POM.
- Netty for the TCP/TLS transport module; `iec60870-core` depends only on `netty-buffer` (`ByteBuf` is
  the codec boundary), not on Netty runtime types.
- JOOU for unsigned integer types in the protocol model.
- SLF4J for logging (no binding is shipped to consumers).
- JUnit 5 for tests; Testcontainers for the lib60870-C interop tests.
- JSpecify for nullness annotations.
- Spotless with google-java-format enforces code style; the check runs as part of `mvn verify`.

## Project Layout

Maven reactor under the parent `pom.xml` (group `com.digitalpetri.iec60870`, artifact
`iec60870-parent`). **One rule governs the module graph:** every module depends on `iec60870-core`
(plus `netty-buffer`, the `ByteBuf` codec boundary) and imports nothing sideways from peer link
layers, octet transports, or `application`. The lone exceptions are the two **assembly modules** —
`iec60870-tcp` and `iec60870-serial` — the sole points where a transport, the link layer(s), and
`application` converge. The published API modules are `core`, `cs104`, `cs101`, `application`,
`transport-tcp`, `transport-serial`, `tcp`, and `serial`; `iec60870-examples` and the
`iec60870-test-*` modules are never published.

**Protocol core**

- `iec60870-core/` — the Netty-runtime-free kernel: the raw ASDU layer (every standard TypeID +
  co-located `Serde`), the `Session`/transport SPIs, `ProtocolProfile`, the neutral `SessionSettings`
  and `OutboundQueuePolicy` markers, and the exceptions. Holds no protocol session engine.
- `iec60870-cs104/` — the 104 link/session code: `ApciSession` (`implements Session`), the
  `Apdu`/`ControlField`/`UFunction` model + `Apdu.Serde`, the `ApduFramer` `Apdu`↔`ByteBuf` bridge,
  `ApciSettings`, and the `Cs104Binding` assembly point (frames/deframes APDUs; routes connection
  loss to `Session.Events.onClosed`).
- `iec60870-cs101/` — the 101 link/session code: the FT1.2 link layers (`Ft12LinkLayer` + the
  balanced/unbalanced engines) that `implement Session` as peers of `ApciSession`, the FT1.2 frame
  model, `LinkSettings`, and the `Cs101Binding` assembly point.
- `iec60870-application/` — the high-level layer (**no Netty**): the `Iec60870Client` /
  `Iec60870Server` facades (interfaces + `Default*`), the command/station/point/catalog model, and
  `ClientConfig` / `ServerConfig`. Speaks only `Asdu` + the `Session` SPI; the session is injected via
  a factory. A `NoNettyInApplicationTest` guard fails the build on any `io.netty.*` type (even
  `ByteBuf`) in its main sources.

**Octet transports** (octet-only; core-only; not assembly points)

- `iec60870-transport-tcp/` — the Netty TCP/TLS transport
  (`NettyClientTransport` / `NettyServerTransport`, the pipeline, the frame decoders).
- `iec60870-transport-serial/` — the jSerialComm serial transport
  (`SerialClientTransport` / `SerialServerTransport`, the FT1.2 deframer, RS-485 options).

**Assembly modules** (user-facing builders; the only convergence points)

- `iec60870-tcp/` — TCP builders `TcpIec104Client` / `TcpIec104Server` and `TcpIec101Client` /
  `TcpIec101Server` (101-over-TCP) in package `com.digitalpetri.iec60870.tcp`. Each builds a Netty
  transport, delegates link/session wiring to `Cs104Binding` / `Cs101Binding`, and hands the
  assembled `Session` (client) / per-connection session factory (server) to the `application` facades.
- `iec60870-serial/` — serial builders `SerialIec101Client` / `SerialIec101Server` in package
  `com.digitalpetri.iec60870.serial`; same shape via `Cs101Binding` over the serial transport.

**Examples & tests**

- `iec60870-examples/` — runnable client, server, raw-ASDU, and TLS examples.
- `iec60870-test-integration/` — cross-module in-JVM client↔server integration tests (including TLS).
- `iec60870-test-interop/` — interop tests against `lib60870-C` peer images via Testcontainers;
  tagged `@Tag("interop")` and excluded from the default build (see Common Commands). Its `docker/`
  subtree is GPLv3; the rest of the project is EPL 2.0.
- `iec60870-test-common/` — internal, test-only fixtures shared across module suites: the
  deterministic `ManualScheduler` virtual clock, the `RecordingEvents` `Session.Events` recorder, the
  frame-capturing `RecordingClientTransport` / `RecordingServerConnection`, the in-JVM
  `LoopbackOctetTransport` and `FaultInjectingOctetTransport` (protocol-neutral, so they serve cs101 /
  serial as readily as 104), and the `ParanoidLeakDetection` JUnit extension. Consumed at `test` scope
  by cs104, application, both transports, tcp, serial, and test-integration; never published.

**Packages & build order.** Source lives under `com.digitalpetri.iec60870`. Kernel packages in
`iec60870-core`: `asdu` (with `asdu.object`, `asdu.element`, `asdu.time`), `address`, `transport`,
`session`. Link/session packages: `cs104` (`ApciSession`, `Apdu`, `ControlField`, `UFunction`,
`ApduFramer`, `ApciSettings`) in `iec60870-cs104`, and `cs101` (`Ft12LinkLayer`, the FT1.2 frame
model, `LinkSettings`, `Cs101Binding`) in `iec60870-cs101`. High-level packages `client`, `server`,
`point`, `catalog` in `iec60870-application`; builder packages `…tcp` / `…serial` in the assembly
modules. Reactor build order: `iec60870-core`, `iec60870-test-common`, `iec60870-cs104`,
`iec60870-cs101`, `iec60870-application`, `iec60870-transport-tcp`, `iec60870-transport-serial`,
`iec60870-tcp`, `iec60870-serial`, `iec60870-examples`, `iec60870-test-integration`,
`iec60870-test-interop`.

**Future module slots (named only — no code, not in `<modules>`):** an optional `TcpIec101Client` /
`TcpIec101Server` 101-over-TCP path already ships in `iec60870-tcp`, so the remaining genuinely-future
slot is a dedicated 101-over-TCP umbrella only if the user base ever needs `cs104` and `cs101` split
across separate artifacts. This is a documented slot only; this effort adds no code for it.

Keep new modules under the parent build and centralize dependency/plugin versions in the root POM.

### Architecture invariant

Keep the `iec60870-core` public API free of Netty *runtime* types (channels, event loops, TLS
engines) — those belong to `iec60870-transport-tcp`. Core deliberately depends on `netty-buffer`, so
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
  `TESTCONTAINERS_RYUK_DISABLED=true mise exec -- mvn -pl iec60870-test-interop -am -Pinterop test`
  (see `iec60870-test-interop/README.md` for details)

## Additional References

Before writing code or Javadoc, read the applicable references below. Treat them as required
project instructions, not optional background material.

- Java coding conventions: `docs/java-coding-conventions.md`
- Documentation guidelines: `docs/documentation-guidelines.md`
- Running tests / module-targeting flags: `docs/running-tests.md`
- Interop test setup (Docker/Testcontainers): `iec60870-test-interop/README.md`

### Architecture Documentation

`docs/architecture/` documents the system as built; start with `docs/architecture/README.md`, which
indexes the rest. Read the relevant document before changing behavior in that area:

- `overview.md` — what IEC 104 is, the two-layer API philosophy, and a component map.
- `modules-and-dependencies.md` — the core-vs-transport split and the rules that keep Netty runtime
  types out of core.
- `two-layer-api.md` — the raw layer (`Asdu`, `Cause`, `InformationObject` records, `Serde` codecs)
  vs. the high-level layer (`Iec60870Client` / `Iec60870Server`, station/point model, commands, events).
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
  `mise exec -- mvn -q -pl iec60870-core test -Dtest=ClassName` or
  `mise exec -- mvn -q -pl iec60870-transport-tcp -am test -Dtest=ClassName`.
- For documentation-only changes, run the relevant lightweight check when one exists and clearly
  state if the full Maven build was skipped.
- If a verification command cannot be run, report the command, the reason it was skipped or failed,
  and any follow-up needed.
