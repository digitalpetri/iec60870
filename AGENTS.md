# IEC 104

IEC 104 is a Maven / Java 17 implementation of IEC 60870-5-104.

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

- `pom.xml` is the parent Maven project (group `com.digitalpetri.iec60870`, artifact `iec60870-parent`).
- `iec60870-core/` owns the protocol model and the Netty-runtime-free kernel: the raw ASDU layer
  (every standard TypeID with a co-located `Serde`), the `Session` and transport SPIs,
  `ProtocolProfile`, the neutral `SessionSettings` marker, the neutral `OutboundQueuePolicy`, and the
  exceptions. It is protocol-engine-free: the 104 APCI session and its settings live in
  `iec60870-cs104`.
- `iec60870-cs104/` owns the genuinely-104 link/session code: the `ApciSession` (which
  `implements Session`), the `Apdu` / `ControlField` / `UFunction` model with the `Apdu.Serde`
  codec, the `ApduFramer` `Apdu`↔`ByteBuf` bridge, `ApciSettings`, and `Cs104Binding` — the
  assembly point that wires an `ApciSession` to a core octet transport handle (framing outbound
  APDUs, deframing inbound frames, routing connection loss to `Session.Events.onClosed`). It depends
  on `iec60870-core` (and `netty-buffer` for the `ByteBuf` codec boundary), imports nothing from
  `transport-tcp` or `application`, and is a published API module.
- `iec60870-application/` owns the high-level layer (NO Netty): the `Iec60870Client` /
  `Iec60870Server` facades (interfaces + `Default*`), the command/station/point/catalog model, and
  the `ClientConfig` / `ServerConfig`. It depends on `iec60870-core` only and speaks purely in terms
  of `Asdu` + the `Session` SPI; the protocol-specific session and its wire framing are injected
  through a session factory. A `NoNettyInApplicationTest` guard fails the build if any `io.netty.*`
  type (even `ByteBuf`) appears in its main sources.
- `iec60870-transport-tcp/` owns the Netty-backed TCP/TLS transport and the user-facing
  `TcpIec104Client` / `TcpIec104Server` builders. The builders are the **sole 104 assembly point**:
  they construct the `NettyClientTransport` / `NettyServerTransport`, then delegate the 104 session
  wiring to `Cs104Binding` (in `cs104`) and hand the assembled `Session` (client) / per-connection
  session factory (server) to the `application` facades. The builders contain no `new ApciSession(...)`
  and no direct `ApduFramer` call. The Netty octet classes (decoder, pipeline, transports) stay free
  of any `cs104`/`application` import; only the `Tcp*` builder types reference `cs104` + `application`.
- `iec60870-examples/` holds runnable client, server, raw-ASDU, and TLS examples.
- `iec60870-tests/` owns cross-module in-JVM client↔server integration tests (including TLS).
- `iec60870-interop/` holds interoperability tests that drive the library against `lib60870-C` peer
  images via Testcontainers; tagged `@Tag("interop")` and excluded from the default build (see
  Common Commands). Its `docker/` subtree is GPLv3; the rest of the project is EPL 2.0.

Modules are declared in the parent POM in build order: `iec60870-core`, `iec60870-cs104`,
`iec60870-application`, `iec60870-transport-tcp`, `iec60870-examples`, `iec60870-tests`,
`iec60870-interop`. Source lives under `com.digitalpetri.iec60870`; the kernel packages in
`iec60870-core` are `asdu` (with `asdu.object`, `asdu.element`, `asdu.time`), `codec`, `address`,
`transport`, and `session`. The 104 link/session package `cs104` (`ApciSession`, `Apdu`,
`ControlField`, `UFunction`, `ApduFramer`, `ApciSettings`) lives in `iec60870-cs104`, while the
high-level packages `client`, `server`, `point`, and `catalog` live in `iec60870-application`.

**Future module slots (named only — no code, not in `<modules>`):** `iec60870-cs101`
(`com.digitalpetri.iec60870.cs101`, → core) would hold an `Ft12LinkLayer` that `implements` the core
`Session` SPI as a **peer of `ApciSession`** (FT1.2 framing + `LinkSettings`), and
`iec60870-transport-serial` (`com.digitalpetri.iec60870.transport.serial`) would hold a
`SerialOctetTransport` implementing the core octet transport SPI — its octet classes depending on
core only, the same octet-classes-stay-core-only rule `transport-tcp` follows. The corresponding
future builders are `SerialIec101Client` / `SerialIec101Server` (assembling `{Ft12LinkLayer(cs101) +
SerialOctetTransport}`) and an optional `TcpIec101Client` / `TcpIec101Server` (101-over-TCP reusing
`transport-tcp`). These are documented slots only; this effort adds no code for them.

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
  `TESTCONTAINERS_RYUK_DISABLED=true mise exec -- mvn -pl iec60870-interop -am -Pinterop test`
  (see `iec60870-interop/README.md` for details)

## Additional References

Before writing code or Javadoc, read the applicable references below. Treat them as required
project instructions, not optional background material.

- Java coding conventions: `docs/java-coding-conventions.md`
- Documentation guidelines: `docs/documentation-guidelines.md`
- Running tests / module-targeting flags: `docs/running-tests.md`
- Interop test setup (Docker/Testcontainers): `iec60870-interop/README.md`

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
