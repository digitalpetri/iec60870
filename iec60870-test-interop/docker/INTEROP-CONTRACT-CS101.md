<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# IEC 60870-5-101 Interop Contract (BALANCED + UNBALANCED)

This file is the **source of truth** for the lib60870-C CS101 interop peer used by the
`iec60870-test-interop` Testcontainers tests `Cs101BalancedInteropTest` and `Cs101UnbalancedInteropTest`.
It is the serial sibling of [`INTEROP-CONTRACT.md`](INTEROP-CONTRACT.md) (CS104): the
application-layer point image, interrogation groups, command partitioning, and spontaneous traffic
are **reused verbatim** from that contract. This document pins only the things that differ for a
serial link: the link mode and addressing, the serial parameters, the PTY/TCP bridge topology, the
FT1.2 behaviors, and the log markers / run env vars.

Sections 1–5 describe the **balanced** link (the default). [Section 6](#6-unbalanced-mode) describes
the **unbalanced** link, selected with `INTEROP_CS101_MODE=unbalanced`; it reuses everything in
sections 2–4 unchanged and pins only the unbalanced link addressing, class-1/class-2 assignment, and
ACD behavior.

The custom C driver `lib60870c/interop_cs101.c` implements exactly this contract. lib60870-C is
GPLv3; the driver `#include`s its headers and statically links `liblib60870.a`, so it forms a
combined work and is distributed only as a standalone serial peer binary inside the interop Docker
image. Everything under `docker/` is therefore GPL-3.0-or-later (see `docker/LICENSE.md` and the
`SPDX-License-Identifier` headers).

- Peer library: **lib60870-C** (MZ Automation), pinned tag **v2.3.5** (see `lib60870c/Dockerfile`).
- Transport: **IEC 60870-5-101 balanced (FT1.2) over an emulated serial line**, 9600 8E1.
- Application-layer sizing: **COT = 1 octet (no originator address), CA = 1 octet, IOA = 2 octets**,
  matching the Java side's `ProtocolProfile(1, 1, 2, 255)`. This is narrower than the CS104 defaults
  (CA = 2, IOA = 3, COT = 2); the CS101 peer sets `sizeOfCOT = 1`, `sizeOfCA = 1`, `sizeOfIOA = 2` on
  its `CS101_AppLayerParameters`.

> **Logging note:** as in CS104, the C peer logs with `printf` (fully buffered to a pipe under
> `docker logs`), so it is run under **`stdbuf -oL -eL`** — the container entrypoint does this — so
> log lines stream for Testcontainers `Wait.forLogMessage(...)`.

---

## 1. Link mode and addressing

| Parameter | Value |
|-----------|-------|
| Link mode | **BALANCED** (`IEC60870_LINK_LAYER_BALANCED`) |
| Link (FT1.2) address | **1** |
| Link address length | **1 octet** (`LinkLayerParameters.addressLength = 1`) |
| Common address (CA) | **1** |

Both stations use the **same** link address `1` (the Java side's `LinkSettings.balanced()` default
`linkAddress = 1`). In balanced point-to-point there is exactly one peer in each direction, so a
single shared link address is sufficient; the C peer sets both its own address and the other
station's address to `1`:

- **C master:** `CS101_Master_setOwnAddress(m, 1)` + `CS101_Master_useSlaveAddress(m, 1)`.
- **C slave:** `CS101_Slave_setLinkLayerAddress(s, 1)` + `CS101_Slave_setLinkLayerAddressOtherStation(s, 1)`.

**DIR bit.** The controlling station sends DIR = 1, the controlled station sends DIR = 0:

- When the C peer is the **master** (controlling), `CS101_Master_setDIR(m, true)`; the Java side is the
  controlled **server** (`Ft12LinkLayer.Role.SERVER`, DIR = 0).
- When the C peer is the **slave** (controlled), `CS101_Slave_setDIR(s, false)`; the Java side is the
  controlling **client** (`Ft12LinkLayer.Role.CLIENT`, DIR = 1).

---

## 2. Serial parameters and FT1.2 behaviors

| Parameter | Value |
|-----------|-------|
| Baud rate | **9600** |
| Data bits / parity / stop bits | **8 / EVEN / 1** ("8E1", required by FT1.2) |
| Single-character ACK | **enabled** (`useSingleCharACK = true`, the `0xE5` short frame) |
| ACK timeout | 1000 ms (`timeoutForAck`) — generous for the bridged link |
| Repeat timeout | 2000 ms (`timeoutRepeat`) |

The serial port is created with `SerialPort_create(device, 9600, 8, 'E', 1)`. The Java side uses the
matching 8E1 defaults of `SerialPortConfig` / `LinkSettings.balanced()` (single-char ACK on).

**Balanced bring-up.** The link is brought up by the FT1.2 balanced link-reset handshake
(reset-of-remote-link, FC 0). The controlling station initiates it; the controlled station reacts.
The C peer drives its link layer with a background thread (`CS101_Master_start` /
`CS101_Slave_start`), which retries the bring-up until the peer responds, so the peer tolerates
startup ordering (it may come up before the Java side is reading the line).

---

## 3. PTY / TCP bridge topology

A serial line is point-to-point, but Testcontainers exposes TCP ports, not serial devices. socat
bridges the gap on **both** ends. The container entrypoint
(`lib60870c/cs101-entrypoint.sh`) runs, in the background:

```
socat PTY,link=/dev/ttyCS101,raw,echo=0,b9600  TCP-LISTEN:2404,reuseaddr,nodelay
```

It creates a pseudo-terminal, symlinks the slave side to `/dev/ttyCS101` (the device the C peer
opens), and exposes the other end as `TCP-LISTEN:2404`. Testcontainers maps container port 2404 to a
host port. The **host** test then runs its own:

```
socat PTY,link=<host pty>,raw,echo=0  TCP:localhost:<mappedPort>
```

so the complete octet path is:

```
Java <-> host PTY <-> host socat <-> TCP <-> (docker port map) <-> container socat <-> /dev/ttyCS101 <-> C peer
```

The Java `SerialIec101Client` / `SerialIec101Server` opens `<host pty>` as its serial device.

> **Host socat is required.** The test that drives this bridge `Assumptions.assumeTrue`s on
> `which socat` and **skips cleanly** when the host has no `socat` (install it with `brew install
> socat` or the platform equivalent to run the test).

---

## 4. Application-layer contract (reused from CS104)

The point image, interrogation groups, counter interrogation, read, command accept/reject
partitioning, return information, and the 2-second periodic update are **identical** to
[`INTEROP-CONTRACT.md`](INTEROP-CONTRACT.md) sections 2–8, with the CS101 sizing from above (CA = 1,
IOA = 2, COT = 1). In summary:

- Monitor image at IOAs `1000`–`1071`, fixed values per CS104 contract section 2.
- Station interrogation (QOI 20) → all seven non-time points, one ASDU per point; group 1 (QOI 21)
  the same; group 2 (QOI 22) the seven time-tagged points reported via their non-time TypeIDs.
- Counter interrogation (general / group 1) → both integrated totals (IOAs 1070/1071, value 1000).
- Read (C_RD_NA_1) of any monitor IOA → that point at its native (time/non-time) TypeID, COT REQUEST.
- Commands: IOA **2000–2999 → ACCEPT** (ACT_CON positive), IOA **3000 → REJECT** (ACT_CON negative).
  A single command accepted mirrors onto IOA 1000, a setpoint-short onto IOA 1060 (RETURN_INFO_REMOTE).
- Periodic scaled measured value (M_ME_NB_1) at IOA 1050, COT PERIODIC, every 2 s.

The C slave enqueues spontaneous / return-info / periodic ASDUs with
`CS101_Slave_enqueueUserDataClass1` (the balanced slave's send path); the per-handler confirmation
flow (`IMasterConnection_sendACT_CON` / `sendACT_TERM` / `sendASDU`) is identical to the CS104 driver.

---

## 5. Roles, run env vars, and log markers

One binary, `interop_cs101`, selects its role from `INTEROP_CS101_ROLE` (`slave` is the default):

| Role | C peer is | Java side under test | Driven by |
|------|-----------|----------------------|-----------|
| `slave`  | controlled station (outstation) | `SerialIec101Client` (master)  | the Java client's requests |
| `master` | controlling station             | `SerialIec101Server` (slave)   | the C master's scripted sequence |

Env vars consumed by the peer / entrypoint:

| Env | Default | Meaning |
|-----|---------|---------|
| `INTEROP_CS101_MODE`   | `balanced` | FT1.2 link mode (`balanced` or `unbalanced`); see [section 6](#6-unbalanced-mode) |
| `INTEROP_CS101_ROLE`   | `slave` | role to run (`slave` or `master`) |
| `INTEROP_CS101_DEVICE` | `/dev/ttyCS101` | serial device the peer opens (created by socat) |
| `INTEROP_CS101_BAUD`   | `9600`  | baud rate |
| `INTEROP_CS101_TCP_PORT` | `2404` | container-side TCP-LISTEN port (entrypoint) |
| `INTEROP_CA`           | `1`     | common address |
| `INTEROP_ACCEPT_IOA`   | `2000`  | IOA expected to be ACCEPTED (master role) |
| `INTEROP_REJECT_IOA`   | `3000`  | IOA expected to be REJECTED (master role) |

The `INTEROP_CS101_PEER START` / `READY` markers carry `mode=<balanced|unbalanced>` and
`role=<slave|master>`, giving a distinct readiness marker per mode/role (for example
`INTEROP-CS101-PEER READY role=slave mode=unbalanced ca=1 linkAddr=1`).

Stable, greppable log markers (anchor `Wait.forLogMessage(...)` on these substrings):

| Marker | Meaning |
|--------|---------|
| `INTEROP-CS101-BRIDGE ... ready` | socat created the PTY; the peer is being launched |
| `INTEROP-CS101-PEER READY role=<slave\|master>` | the C peer opened the serial port and started its run loop |
| `LINK available` / `LINK idle` / `LINK error` | FT1.2 link-layer state changes |
| (slave) `IC station`, `CMD ... ACCEPT\|REJECT ...`, `PERIODIC ioa=1050 value=N`, `RETURN-INFO ioa=N` | same handler markers as the CS104 server |
| (master) `MASTER RECVD type=... cot=... neg=... elems=... ioa0=...` | each received ASDU |
| (master) `PASS:` / `FAIL:` | per-step scripted result |
| (master) `INTEROP-CS101-MASTER RESULT pass=<n> fail=<n>` | final scripted result line |

### Master scripted sequence (role = master)

The C master, once `LINK available`, runs and logs `PASS:`/`FAIL:` for:

1. `link available` — the balanced link reached the available state.
2. `station interrogation (ACT_CON + data)` — station IC returns ACT_CON + data.
3. `accept command confirmed (P/N=0)` — single command ON, direct, to `INTEROP_ACCEPT_IOA`.
4. `reject command negatively confirmed (P/N=1)` — single command ON, direct, to `INTEROP_REJECT_IOA`.
5. `spontaneous data observed` — at least one COT SPONTANEOUS/PERIODIC ASDU from the slave.

It then prints `INTEROP-CS101-MASTER RESULT pass=<n> fail=<n>` and exits non-zero if `fail != 0`.

---

## 6. UNBALANCED mode

Selected with `INTEROP_CS101_MODE=unbalanced`, this runs the same `interop_cs101` binary against
lib60870-C's **unbalanced** (master/secondary) FT1.2 link layer
(`IEC60870_LINK_LAYER_UNBALANCED`) instead of the balanced machine. It is the peer for
`Cs101UnbalancedInteropTest`. The serial parameters (9600 8E1), the PTY/TCP bridge topology (section
3), the application-layer point image, interrogation groups, command accept/reject partitioning, and
the periodic update (sections 2 and 4) are **unchanged**. Only the link layer differs, as pinned
below.

### 6.1 Link mode and addressing

| Parameter | Value |
|-----------|-------|
| Link mode | **UNBALANCED** (`IEC60870_LINK_LAYER_UNBALANCED`) |
| Link (FT1.2) address length | **1 octet** (`LinkLayerParameters.addressLength = 1`) |
| Secondary (slave) link address | **1** |
| Common address (CA) | **1** |
| Broadcast (all-secondaries) address | **255** (one-octet) |

A **single secondary station** at link address `1` (single-drop). There is no DIR bit in unbalanced
transmission — primary frames carry RES (bit 7 = 0) — so `CS101_Master_setDIR` /
`CS101_Slave_setDIR` are not used. The master polls and addresses commands by the secondary's link
address, which by contract equals the common address `1`.

- **C master:** `CS101_Master_create(port, NULL, NULL, IEC60870_LINK_LAYER_UNBALANCED)`,
  `CS101_Master_addSlave(m, 1)`, `CS101_Master_useSlaveAddress(m, 1)`, then a single-threaded loop of
  `CS101_Master_run(m)` + `CS101_Master_pollSingleSlave(m, 1)` (the unbalanced primary link layer is
  not internally locked, so it is driven from one thread, as in lib60870-C's
  `cs101_master_unbalanced` example). The single registered secondary is brought up automatically by
  `CS101_Master_run` (request-status-of-link → reset-of-remote-link → available).
- **C slave:** `CS101_Slave_create(port, NULL, NULL, IEC60870_LINK_LAYER_UNBALANCED)`,
  `CS101_Slave_setLinkLayerAddress(s, 1)`, then `CS101_Slave_start(s)` (a purely reactive secondary
  driven by the same background thread the balanced slave uses).

On the Java side:

- **Our master** (`Cs101UnbalancedInteropTest#clientVsCSlave`):
  `LinkSettings.unbalanced().slaveAddresses(List.of(1)).pollInterval(Duration.ofMillis(500))` — polls
  secondary `1`; commands to common address `1` are routed to it.
- **Our slave** (`Cs101UnbalancedInteropTest#serverVsCMaster`):
  `LinkSettings.unbalanced().linkAddress(1)` — answers on link address `1`, the address the C master
  polls.

### 6.2 Class-1 / class-2 assignment and ACD behavior

In unbalanced transmission the secondary **never initiates**: it buffers data and the master pulls
it with class-2 polls (request-class-2, FC11) and, when escalated, class-1 polls (request-class-1,
FC10). Each secondary response advertises an **access-demand (ACD)** bit when class-1 (event) data
is still pending, which the master uses to escalate to a class-1 poll.

- **C slave (our master polls it):** the **periodic** M_ME_NB_1 at IOA 1050 is enqueued as
  **class-2** (`CS101_Slave_enqueueUserDataClass2`), so our master's regular class-2 poll delivers it
  as COT `PERIODIC`. All command-direction responses — interrogation `ACT_CON` + the per-point data
  + `ACT_TERM`, command confirmations, return-information, end-of-initialization — go to **class-1**
  (lib60870-C's `IMasterConnection_sendASDU` enqueues class-1), so the slave asserts ACD and our
  master drains them via class-1 escalation off its class-2 poll.
- **Our slave (C master polls it):** the engine routes `Cause.SPONTANEOUS` ASDUs to **class-1** and
  every other cause to **class-2**. The contract's periodic publish uses `Cause.PERIODIC`, so it is
  **class-2** and the C master receives it on its regular class-2 poll. Interrogation and command
  `ACT_CON` (activation-confirmation cause) are likewise class-2. The C master also auto-requests
  class-1 data whenever a response carries ACD, so any class-1 data is drained too.

### 6.3 Roles, markers, and the scripted sequence

The role table, log markers, and the master's scripted `PASS:`/`FAIL:` steps + final
`INTEROP-CS101-MASTER RESULT pass=<n> fail=<n>` line (section 5) are **identical** to the balanced
master, with `mode=unbalanced` in the `START` / `READY` markers. The unbalanced master role drives
the same five scripted steps (link available, station interrogation, accept command, reject command,
spontaneous data) on its single-threaded poll loop.

| Role (mode=unbalanced) | C peer is | Java side under test | Driven by |
|------|-----------|----------------------|-----------|
| `slave`  | unbalanced secondary | `SerialIec101Client` (unbalanced master) | the Java master's polls + requests |
| `master` | unbalanced primary   | `SerialIec101Server` (unbalanced slave)  | the C master's scripted poll loop |

---

## 7. Exact run commands

Image (built by `lib60870c/build.sh`): `lib60870c-interop:v2.3.5`. The CS101 peer and its entrypoint
live in `/usr/local/bin` alongside the CS104 binaries.

**C slave (our Java CLIENT tests against this):**

```bash
docker run --rm -p 2404:2404 \
  -e INTEROP_CS101_ROLE=slave \
  lib60870c-interop:v2.3.5 \
  cs101-entrypoint.sh
# then on the host:
#   socat PTY,link=/tmp/ttyHOST,raw,echo=0 TCP:localhost:<mappedPort>
#   SerialIec101Client.builder().serialPort("/tmp/ttyHOST")...
```

**C master (drives our Java SERVER):**

```bash
docker run --rm -p 2404:2404 \
  -e INTEROP_CS101_ROLE=master \
  lib60870c-interop:v2.3.5 \
  cs101-entrypoint.sh
```

**Unbalanced peers** add `-e INTEROP_CS101_MODE=unbalanced`:

```bash
# C unbalanced slave (our Java unbalanced CLIENT tests against this):
docker run --rm -p 2404:2404 \
  -e INTEROP_CS101_MODE=unbalanced -e INTEROP_CS101_ROLE=slave \
  lib60870c-interop:v2.3.5 cs101-entrypoint.sh

# C unbalanced master (drives our Java unbalanced SERVER):
docker run --rm -p 2404:2404 \
  -e INTEROP_CS101_MODE=unbalanced -e INTEROP_CS101_ROLE=master \
  lib60870c-interop:v2.3.5 cs101-entrypoint.sh
```

The CS104 binaries (`interop_server`, `interop_client`, stock `cs104_server` / `simple_client`) and
their contract are unchanged.
