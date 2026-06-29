# iec60870-test-interop

Cross-implementation interoperability tests that exercise this library against the
[lib60870-C](https://github.com/mz-automation/lib60870) (MZ Automation) IEC 60870-5 reference
implementation. The reference peer runs as a separate process inside a Docker container, started and
torn down by [Testcontainers](https://testcontainers.com/). The matrix covers CS104 plaintext/TLS,
CS101 serial balanced/unbalanced, and plaintext 101-over-TCP through the same lib60870-C CS101 peer.

Every assertion is anchored to the wire contract in
[`docker/INTEROP-CONTRACT.md`](docker/INTEROP-CONTRACT.md) and
[`docker/INTEROP-CONTRACT-CS101.md`](docker/INTEROP-CONTRACT-CS101.md), which are the source of truth
for the addresses, values, bridge modes, and confirmation behavior the tests expect. The custom C
driver programs (`docker/lib60870c/interop_server.c`, `docker/lib60870c/interop_client.c`,
`docker/lib60870c/interop_cs101.c`) implement those contracts.

## Requirements

- **Docker** must be running. The peer image is built on the fly from `docker/lib60870c` via a
  Testcontainers `ImageFromDockerfile`; the first build shallow-clones and compiles lib60870-C
  (a few minutes), after which the Docker layer cache makes reruns fast.
- Java 17 and Maven (managed via `mise`, see the repo root `.mise.toml`).

## What each test covers

These tests are tagged `@Tag("interop")` and are **excluded from the default build**. Run them with
the `-Pinterop` profile (see below).

| Class | Wire path | Direction | Coverage shape |
|-------|-----------|-----------|----------------|
| `ClientVsLib60870ServerInteropTest` | CS104 plaintext TCP | Java client -> C server | broad application contract |
| `ServerVsLib60870ClientInteropTest` | CS104 plaintext TCP | C client -> Java server | scripted server contract |
| `Cs104TlsInteropTest` | CS104 TLS/mTLS TCP | both directions | focused TLS + application contract |
| `AbnormalClientVsLib60870ServerInteropTest` | CS104 plaintext TCP through Toxiproxy | Java client -> C server | failure/recovery outcomes |
| `Cs101BalancedInteropTest` | CS101 serial balanced | both directions | broad serial client + scripted server contract |
| `Cs101UnbalancedInteropTest` | CS101 serial unbalanced | both directions | broad serial client + scripted server contract |
| `Cs101OverTcpInteropTest` | CS101 balanced over plaintext TCP | both directions | broad TCP client + scripted server contract |

### `ClientVsLib60870ServerInteropTest` — BROAD scenario

Our Java `Iec60870Client` (controlling station) drives the lib60870-C `interop_server` (controlled
station). Coverage:

- **Station / group-1 interrogation** round-trips **all seven non-time monitor types**
  (single, double, step, 32-bit bitstring, normalized, scaled, short-float at IOAs
  1000/1010/1020/1030/1040/1050/1060), decoding and asserting each value plus the response COT and
  `ACT_CON`/`ACT_TERM` framing.
- **Group-2 interrogation** round-trips **all seven time-tagged points** at the time-tagged IOAs
  (1001/1011/.../1061), which per CS101 are reported with their non-time TypeIDs; the decoded value
  is asserted for each.
- **Read of every monitor IOA** round-trips each point at its **native** TypeID, including the
  CP56Time2a-tagged TypeIDs (M_SP_TB_1, M_DP_TB_1, M_ST_TB_1, M_BO_TB_1, M_ME_TD_1, M_ME_TE_1,
  M_ME_TF_1) that an interrogation response cannot carry. Asserts the carrying ASDU's TypeID, the
  decoded value (normalized within ~1 LSB; short-float within a small epsilon), and timestamp
  presence for the time-tagged forms.
- **Counter interrogation** returns both integrated totals (IOAs 1070/1071, value 1000).
- **Every command type** (single, double, regulating-step, setpoint normalized/scaled/short-float,
  bitstring) to an accept IOA is positively confirmed. Commands with a select/execute qualifier are
  covered in both **direct-execute** and **select-before-operate** modes; bitstring is direct-only
  because `C_BO_NA_1` carries no select/execute qualifier. A command to the reject IOA 3000 is
  negatively confirmed.
- **Clock sync**, **test command**, **reset process** (which re-issues End-of-Initialization), and a
  **periodic** scaled measured value (COT PERIODIC).

### `ServerVsLib60870ClientInteropTest` — LIMITED scenario

The mirror: the lib60870-C `interop_client` (controlling station) drives our Java `Iec60870Server`
(controlled station) through a scripted sequence and prints `PASS:`/`FAIL:` markers; the test
asserts the script completes with `fail=0` and that each supported step passed.

### `Cs104TlsInteropTest` — CS104 TLS / mTLS scenario

The CS104 TLS sibling of the plaintext tests. Each test generates a temporary CA plus server/client
certificates, copies the PEM files into the lib60870-C container, and enables the C peer with
`INTEROP_TLS=1` (plaintext remains the default for all other tests).

- **Our TLS client vs the C TLS server** — Java `TcpIec104Client` connects with a generated client
  certificate, trusts only the generated CA, disables hostname verification only because the
  Testcontainers endpoint hostname is dynamic, starts data transfer, runs station interrogation, and
  verifies one accepted and one rejected command.
- **The C TLS client vs our TLS server** — `interop_client` connects with its generated client
  certificate to Java `TcpIec104Server`; the test verifies the scripted station/counter/read/command
  sequence and asserts that Java exposed the generated peer certificate.

### `AbnormalClientVsLib60870ServerInteropTest` — ABNORMAL-path scenarios

Our Java `Iec60870Client` drives the lib60870-C `interop_server` through a
[Toxiproxy](https://github.com/Shopify/toxiproxy) that sits between them, so the link and the peer can
be disrupted mid-session. Every assertion is on an *outcome* (a `ConnectionClosed` event was
published, a request failed with `ConnectionClosedException`, a fresh interrogation round-tripped
after recovery), never on a wall-clock duration. Coverage:

- **Network partition mid-request** — the proxy is disabled while an interrogation is in flight; the
  pending request fails and a `ConnectionClosed` event follows.
- **Peer `SIGKILL` mid-request** — the peer container is killed while an interrogation is in flight;
  the in-flight call either completes just before the kill lands or fails cleanly afterward, but a
  `ConnectionClosed` event always follows.
- **Peer restart with auto-reconnect** — the peer is restarted behind the stable proxy port and the
  client's persistent transport transparently reconnects and resumes.
- **Half-open stall** — a frozen link (no FIN) leaves a half-open connection that trips the `t1` timer,
  and the client closes the connection.

### `Cs101BalancedInteropTest` — IEC 60870-5-101 BALANCED scenario

The serial sibling of the CS104 tests: OUR `SerialIec101Client` / `SerialIec101Server` talk to the
lib60870-C CS101 **balanced** peer (`docker/lib60870c/interop_cs101.c`) over a socat-bridged virtual
serial line. Because a serial line is point-to-point but Testcontainers exposes TCP ports, `socat`
bridges both ends: the container entrypoint bridges the C peer's PTY to `TCP-LISTEN:2404`, and a
**host** `socat` bridges a host PTY to the mapped port (`Java <-> host PTY <-> host socat <-> TCP <->
container socat <-> /dev/ttyCS101 <-> C peer`). Both directions are covered:

- **Our client (master) vs the C slave** — station/group 1/group 2 interrogation, counter
  interrogation, read of every monitor IOA, non-time command types in direct mode plus SBO where the
  command type supports select/execute, reject command, clock sync, test command, reset process, and
  periodic update.
- **Our server (slave) vs the C master** — the C master runs a scripted station interrogation,
  counter interrogation, read, accept/reject command, and spontaneous/periodic observation sequence
  and prints `PASS:`/`FAIL:` + an `INTEROP-CS101-MASTER RESULT` line.

Assertions are anchored to [`docker/INTEROP-CONTRACT-CS101.md`](docker/INTEROP-CONTRACT-CS101.md)
(CS101 sizing `ProtocolProfile(1, 1, 2, 255)`, balanced `LinkSettings`, link address 1). The test
**requires `socat` on the host**: it `Assumptions.assumeTrue`s on `which socat` and **skips cleanly**
(not fails) when `socat` is absent. Install it with `brew install socat` (or the platform
equivalent) to run it fully.

### `Cs101UnbalancedInteropTest` — IEC 60870-5-101 UNBALANCED scenario

The unbalanced serial sibling uses the same C peer and point contract with
`INTEROP_CS101_MODE=unbalanced`. The Java client side is the unbalanced primary that polls the C
secondary, and the Java server side is the unbalanced secondary polled by the C primary. It covers
the same broad Java-client assertions and scripted C-master server assertions as the balanced serial
test. It also requires host `socat` and skips cleanly when it is absent.

### `Cs101OverTcpInteropTest` — IEC 60870-5-101-over-TCP plaintext scenario

The 101-over-TCP tests run the lib60870-C CS101 balanced peer behind the container's socat bridge,
but Java uses `TcpIec101Client` / `TcpIec101Server` instead of a serial port:

- **Our TCP 101 client vs the C slave** — container bridge mode `listen` exposes
  `/dev/ttyCS101` as `TCP-LISTEN:2404`; Java connects directly to the mapped port and runs the broad
  client contract. No host `socat` is required.
- **The C master vs our TCP 101 server** — container bridge mode `connect` bridges `/dev/ttyCS101`
  to `TCP:${INTEROP_CS101_TCP_HOST}:${INTEROP_CS101_TCP_PORT}` and drives the Java server through the
  scripted master contract. No host `socat` is required.

## Building the lib60870-C image

The interop tests build the image automatically the first time they run, so a manual build is
usually unnecessary. To build (or rebuild) it by hand:

```bash
cd docker/lib60870c
./build.sh                      # builds lib60870c-interop:v2.3.5 (default ref)
# LIB60870_REF=v2.3.6 ./build.sh  # pin a different lib60870 tag
# IMAGE=myrepo/lib60870c ./build.sh
```

A standalone smoke test (server peer + stock `simple_client`, on a throwaway Docker network) is in
`docker/lib60870c/smoke-test.sh`.

## Running the interop tests

Testcontainers auto-detects the active Docker socket from your current `docker context`, so no
`DOCKER_HOST` or `docker.client.strategy` override is needed. On macOS with Docker Desktop, skip the
Ryuk resource-reaper container (it can have socket-mount trouble there; the tests stop their own
containers in `@AfterAll`).

**From the repository root:**

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mise exec -- mvn -pl iec60870-test-interop -am -Pinterop test
```

**From the `iec60870-test-interop/` module directory**

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mise exec -- mvn -f ../pom.xml -pl iec60870-test-interop -am -Pinterop test
```

> **If Testcontainers cannot find the daemon** and your active context is not the `default`
> `/var/run/docker.sock`, check `~/.testcontainers.properties` for a stale `docker.client.strategy`
> pin (e.g. `UnixSocketClientProviderStrategy`, which only looks at `/var/run/docker.sock`). Remove
> that line so the active context is used, or set `DOCKER_HOST` to the right socket.

## Default build excludes these tests

Without `-Pinterop`, the `interop` tag is excluded, so the module runs **0 tests** and never touches
Docker:

```bash
mise exec -- mvn -pl iec60870-test-interop test   # -> Tests run: 0
```

## Notes

- **TLS scope.** The lib60870-C image is built with mbedTLS and the CS104 peers opt into mTLS with
  `INTEROP_TLS=1` plus per-test PEM files under `INTEROP_TLS_CERT_DIR` (default `/interop-tls`).
  Native lib60870-C CS101 TLS is intentionally not covered: the lib60870-C CS101 APIs are serial-port
  based. Java `TcpIec101*` TLS is covered by loopback integration tests in `iec60870-test-integration`.
- **`stdbuf -oL -eL`** wraps every peer command. The lib60870-C drivers log with `printf`, which is
  fully buffered when stdout is a pipe (as under `docker logs`); line buffering makes log markers
  stream in real time for Testcontainers `Wait.forLogMessage(...)`.
- lib60870-C is GPLv3. It is consumed here only as a standalone network peer binary inside the
  Docker image, never linked into the library.

## License

This module's Java sources are licensed under the **Eclipse Public License 2.0**, like the rest of
the project — they only build the peer image and talk to it over TCP, which does not create a
combined work under the GPL.

The `docker/` subtree is the exception. The custom C drivers (`docker/lib60870c/interop_server.c`,
`docker/lib60870c/interop_client.c`) `#include` lib60870-C headers and statically link
`liblib60870.a`, so they form a combined work with the GPLv3 lib60870-C library. Everything under
`docker/` is therefore licensed under the **GNU General Public License v3.0 or later**; see
[`docker/LICENSE.md`](docker/LICENSE.md) and the `SPDX-License-Identifier` headers on those files.
