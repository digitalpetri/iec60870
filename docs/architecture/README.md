# IEC 60870-5 Library — Architecture

This is the architecture reference for the `com.digitalpetri.iec60870` library, a Java 17
implementation of IEC 60870-5-104 and its serial companion standard IEC 60870-5-101. The library is
split into protocol, transport, and assembly modules: a protocol core (`iec60870-core`) that owns the
wire model, codecs, and the `Session`/transport SPIs; a 104 link/session module (`iec60870-cs104`)
that owns the APCI session engine (`ApciSession`); a 101 link module (`iec60870-cs101`) that owns the
FT1.2 link layer (`Ft12LinkLayer`); a high-level application module (`iec60870-application`) that owns
the client/server facades and the station/point model, shared by both profiles; a core-only
Netty-backed octet transport (`iec60870-transport-tcp`) that supplies the TCP/TLS plumbing; a
core-only serial octet transport (`iec60870-transport-serial`) that supplies the serial-port plumbing;
and two assembly modules — `iec60870-tcp` (the user-facing `TcpIec104Client` / `TcpIec104Server` entry
points) and `iec60870-serial` (the `SerialIec101Client` / `SerialIec101Server` entry points) — that
wire the octet transport, link layer, and application facade together. Everything a caller touches
above the socket or serial port lives in the core/cs104/cs101/application modules — none of which use
Netty runtime types; the transport modules are the only place that knows about channels, event loops,
TLS engines, and serial ports.

These documents describe the system **as built**. They are written for someone integrating the
library or extending it, not for someone editing the protocol internals.

## Documents

- [overview.md](overview.md) — what IEC 104 is, the two-layer API philosophy (raw protocol layer
  vs. high-level facade), and a component map of the modules and key packages.
- [modules-and-dependencies.md](modules-and-dependencies.md) — the core-vs-transport split, the
  dependency rules that keep Netty runtime types out of core, and the libraries each module pulls in.
- [two-layer-api.md](two-layer-api.md) — the raw layer (`Asdu`, `AsduType`, `Cause`,
  `InformationObject` records, addresses, co-located `Serde` codecs) and the high-level layer
  (`Iec60870Client` / `Iec60870Server`, station/point model, commands, events, catalog).
- [protocol-coverage.md](protocol-coverage.md) — the coverage matrix: every ASDU type identification
  in the 101/104 tables, its mnemonic, direction, and Java record name, plus the out-of-scope file
  transfer types and why they are raw-layer only.
- [apci-and-timers.md](apci-and-timers.md) — the APCI lifecycle (STARTDT/STOPDT/TESTFR), the I/S/U
  frame formats, the sequence-number method, the `k`/`w` window, and the `t0`–`t3` timers, and how
  `ApciSession` implements them.
- [ft12-link-layer.md](ft12-link-layer.md) — the IEC 60870-5-101 FT1.2 link layer (`Ft12LinkLayer`),
  its frame formats, the balanced and unbalanced link procedures, the stop-and-wait FCB flow control,
  and the `LinkSettings` timers, and how CS101 carries the shared ASDU layer over a serial link.
- [buffers-and-threading.md](buffers-and-threading.md) — buffer ownership and `ByteBuf` release
  rules, plus the threading and callback-serialization model.
- [tls-and-configuration.md](tls-and-configuration.md) — the TLS approach (`TlsOptions`, handshake
  gating, peer certificate exposure) and the configuration/profile types (`ProtocolProfile`,
  `ApciSettings`).
- [errors-and-extensibility.md](errors-and-extensibility.md) — the typed exception model versus
  result objects, when each is used, and the raw send/receive hooks for unmodeled TypeIDs.

## At a glance

| Concern | Where it lives |
|---|---|
| Wire model and codecs | `iec60870-core` packages `.asdu`, `.address` |
| APCI flow-control engine (104) | `iec60870-cs104` `com.digitalpetri.iec60870.cs104.ApciSession` |
| FT1.2 link layer (101) | `iec60870-cs101` `com.digitalpetri.iec60870.cs101.Ft12LinkLayer` |
| High-level client | `iec60870-application` `com.digitalpetri.iec60870.client` |
| High-level server | `iec60870-application` `com.digitalpetri.iec60870.server` |
| Point / catalog model | `iec60870-application` packages `.point`, `.catalog` |
| Transport interfaces (no Netty) | `iec60870-core` `com.digitalpetri.iec60870.transport` |
| Netty TCP/TLS octet transport (core-only) | `iec60870-transport-tcp` |
| Serial octet transport (core-only) | `iec60870-transport-serial` |
| TCP builders (assembly) | `iec60870-tcp` `com.digitalpetri.iec60870.tcp` |
| Serial builders (assembly) | `iec60870-serial` `com.digitalpetri.iec60870.serial` |
