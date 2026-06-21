# iec104-interop

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

Our Java `Iec104Client` (controlling station) drives the lib60870-C `interop_server` (controlled
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

The mirror: the lib60870-C `interop_client` (controlling station) drives our Java `Iec104Server`
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

```bash
mise exec -- mvn -pl iec104-interop -am -Pinterop test
```

On macOS with Docker Desktop, Testcontainers needs these knobs so it talks to the right socket and
does not require the Ryuk resource-reaper:

```bash
DOCKER_HOST=unix:///Users/kevin/.docker/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
mise exec -- mvn -pl iec104-interop -am -Pinterop test \
  -DargLine="-Dapi.version=1.43 -Ddocker.client.strategy=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy"
```

- `DOCKER_HOST` points Testcontainers at the user-mode Docker Desktop socket.
- `TESTCONTAINERS_RYUK_DISABLED=true` skips the Ryuk reaper container (the tests stop their own
  containers in `@AfterAll`).
- The `argLine` pins the Docker API version and forces the
  `EnvironmentAndSystemPropertyClientProviderStrategy` so the `DOCKER_HOST` above is honored.

## Default build excludes these tests

Without `-Pinterop`, the `interop` tag is excluded, so the module runs **0 tests** and never touches
Docker:

```bash
mise exec -- mvn -pl iec104-interop test   # -> Tests run: 0
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
