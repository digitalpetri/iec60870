# iec60870-interop

Cross-implementation interoperability tests that exercise this library against the
[lib60870-C](https://github.com/mz-automation/lib60870) (MZ Automation) IEC 60870-5-104 reference
implementation. The reference peer runs as a separate process inside a Docker container, started and
torn down by [Testcontainers](https://testcontainers.com/); our Java client/server talk to it over
plaintext IEC 60870-5-104 on TCP port 2404.

Every assertion is anchored to the wire contract in
[`docker/INTEROP-CONTRACT.md`](docker/INTEROP-CONTRACT.md), which is the source of truth for the
addresses, values, and confirmation behavior the tests expect. The two custom C driver programs
(`docker/lib60870c/interop_server.c`, `docker/lib60870c/interop_client.c`) implement exactly that
contract.

## Requirements

- **Docker** must be running. The peer image is built on the fly from `docker/lib60870c` via a
  Testcontainers `ImageFromDockerfile`; the first build shallow-clones and compiles lib60870-C
  (a few minutes), after which the Docker layer cache makes reruns fast.
- Java 17 and Maven (managed via `mise`, see the repo root `.mise.toml`).

## What each test covers

These tests are tagged `@Tag("interop")` and are **excluded from the default build**. Run them with
the `-Pinterop` profile (see below).

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
  bitstring) to an accept IOA is positively confirmed in both **direct-execute** and
  **select-before-operate** modes; a command to the reject IOA 3000 is negatively confirmed.
- **Clock sync**, **test command**, **reset process** (which re-issues End-of-Initialization), and a
  **periodic** scaled measured value (COT PERIODIC).

### `ServerVsLib60870ClientInteropTest` — LIMITED scenario

The mirror: the lib60870-C `interop_client` (controlling station) drives our Java `Iec60870Server`
(controlled station) through a scripted sequence and prints `PASS:`/`FAIL:` markers; the test
asserts the script completes with `fail=0` and that each supported step passed.

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
mise exec -- mvn -pl iec60870-interop -am -Pinterop test
```

**From the `iec60870-interop/` module directory**

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mise exec -- mvn -f ../pom.xml -pl iec60870-interop -am -Pinterop test
```

> **If Testcontainers cannot find the daemon** and your active context is not the `default`
> `/var/run/docker.sock`, check `~/.testcontainers.properties` for a stale `docker.client.strategy`
> pin (e.g. `UnixSocketClientProviderStrategy`, which only looks at `/var/run/docker.sock`). Remove
> that line so the active context is used, or set `DOCKER_HOST` to the right socket.

## Default build excludes these tests

Without `-Pinterop`, the `interop` tag is excluded, so the module runs **0 tests** and never touches
Docker:

```bash
mise exec -- mvn -pl iec60870-interop test   # -> Tests run: 0
```

## Notes

- **TLS is plaintext only.** The lib60870-C interop image does **not** compile TLS in; the peer
  speaks plaintext IEC 60870-5-104 on TCP port 2404. The secure-transport path is therefore not
  exercised against this peer (it is covered by the library's own unit tests).
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
