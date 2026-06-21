# Overview

## What IEC 60870-5-104 is

IEC 60870-5-104 (often "IEC 104") is a telecontrol protocol used in electrical substations and other
SCADA systems to exchange process data between a controlling station (the master, a control center)
and a controlled station (the outstation or RTU). It is the TCP/IP profile of IEC 60870-5-101: the
same application layer — the ASDUs (Application Service Data Units) that carry measurements,
status, and commands — runs over a TCP connection (conventionally port 2404) wrapped in a small
link-control layer called the APCI (Application Protocol Control Information).

Two layers therefore sit on every connection:

- **APCI** — the fixed 6-octet-or-more frame header that provides connection start/stop, keep-alive
  testing, and a sliding-window acknowledgement scheme over the otherwise stream-oriented TCP
  connection. See [apci-and-timers.md](apci-and-timers.md).
- **ASDU** — the application payload: a type identification, a cause of transmission, addressing,
  and one or more information objects (single points, measured values, commands, and so on). See
  [protocol-coverage.md](protocol-coverage.md).

A monitor-direction message flows from the controlled station to the controlling station (a
measurement, a status change). A control-direction message flows the other way (a command, an
interrogation request). Data only flows after the controlling station sends `STARTDT` and the
controlled station confirms it.

## The two-layer API philosophy

The library deliberately exposes two levels of abstraction, and a caller can move freely between
them on the same connection.

**The raw protocol layer** models the wire faithfully. `Asdu`, `AsduType`, `Cause`, and the
per-type `InformationObject` records in `com.digitalpetri.iec104.asdu.object` are direct,
immutable representations of what travels on the wire, each with a co-located `Serde` that
encodes and decodes it. A caller who needs full control — conformance testing, private TypeIDs,
unusual cause/qualifier combinations — builds an `Asdu` and sends it with `Iec104Client.send(Asdu)`,
and observes every inbound `Asdu` through the event stream. Nothing is hidden at this layer.

**The high-level facade** turns that wire model into a domain API. `Iec104Client` offers
`interrogate`, `read`, `commands().single(...)`, and `synchronizeClock`; `Iec104Server` hosts
`Station`s with `PointDefinition`s and answers requests from a current-value image. Values are
expressed as `PointValue<T>` with `Quality`, addressed by `PointAddress`, and delivered as typed
`ClientEvent`/`ServerEvent` records. This layer correlates request/response, manages the command
select-before-operate sequence, projects monitor objects onto domain point values, and serializes
event delivery onto a callback executor.

The escape hatch is always present: `Iec104Client.send(Asdu)` / `events()` on the client, and
`ServerContext.send(Asdu)` / `ServerHandler.onRawAsdu(...)` on the server, let an application drop
to the raw layer for anything the facade does not model — without leaving the high-level API.

See [two-layer-api.md](two-layer-api.md) for the concrete types and short code sketches.

## Component map

```
                         iec104-transport-tcp  (Netty: Channel, EventLoopGroup, SslHandler)
                         ┌───────────────────────────────────────────────────────────────┐
                         │  TcpIec104Client.builder()        TcpIec104Server.builder()    │
   user-facing  ───────► │  host/port/tls  ──► NettyClientTransport / NettyServerTransport │
   entry points          │  Iec104FrameDecoder / Iec104FrameEncoder  (the ByteBuf boundary)│
                         └───────────────────────────────────────────────────────────────┘
                                 │ implements                       returns the core interface
                                 ▼ .transport interfaces            (Iec104Client / Iec104Server)
   ┌──────────────────────────────────────────────────────────────────────────────────────────┐
   │ iec104-core  (no Netty channel/handler types; ByteBuf only inside .asdu/.apci Serde)       │
   │                                                                                            │
   │  HIGH-LEVEL FACADE                                                                          │
   │   .client   Iec104Client (+ DefaultIec104Client), CommandService, Command, CommandResult,  │
   │             ClientEvent, InterrogationResult, ClientConfig                                 │
   │   .server   Iec104Server (+ DefaultIec104Server), Station, PointDefinition, StationRegistry,│
   │             ServerHandler, ServerContext, ServerEvent, ServerConfig, request/decision types│
   │   .point    PointValue<T>, PointType, Quality, PointCapability, TimeTagStyle, MonitorMapping │
   │   .catalog  PointCatalog, CatalogEntry, ObservedCatalog, ObservationMode, MergePolicy       │
   │                                                                                            │
   │  APCI SESSION ENGINE                                                                        │
   │   .apci     ApciSession (V(S)/V(R), k/w window, t1/t2/t3), Apdu, ControlField, UFunction    │
   │                                                                                            │
   │  RAW PROTOCOL MODEL + CODECS                                                                │
   │   .asdu          Asdu, AsduType, Cause, InformationObject, InformationObjectCodec(s)         │
   │   .asdu.object   one record per supported TypeID (SinglePointInformation, ... )            │
   │   .asdu.element  reusable elements/qualifiers (Qds, Vti, NormalizedValue, QualifierOf*, ...) │
   │   .asdu.time     Cp56Time2a, Cp24Time2a, Cp16Time2a                                         │
   │   .address       CommonAddress, InformationObjectAddress, OriginatorAddress, PointAddress   │
   │   .codec         TypeCodecRegistry, MutableTypeCodecRegistry  (private-TypeID extension)     │
   │                                                                                            │
   │  CONFIG + ERRORS (package root)                                                             │
   │   ProtocolProfile, ApciSettings, TlsOptions                                                 │
   │   Iec104Exception (+ AsduDecodeException, ProtocolTimeoutException, ConnectionClosed-,       │
   │                    NegativeConfirmation-, UnsupportedAsduType-, SequenceNumberException)     │
   │                                                                                            │
   │  TRANSPORT INTERFACES (no Netty)                                                            │
   │   .transport  ClientTransport, ServerTransport, ServerTransportConnection, TransportListener │
   └──────────────────────────────────────────────────────────────────────────────────────────┘
```

### How a message moves through the stack

Outbound (client issuing a command): the facade (`CommandService`) builds a domain `Command`, maps
it to a wire `Asdu`, and hands it to the `ApciSession`, which wraps it in an I-format `Apdu` honoring
the `k` window. The transport's `Iec104FrameEncoder` calls `Apdu.Serde.encode(...)` into a Netty
`ByteBuf` and writes it to the channel.

Inbound (a spontaneous measurement): the transport's `Iec104FrameDecoder` frames on the `0x68`
start/length, calls `Apdu.Serde.decode(...)` to produce an `Apdu`, and delivers it through the
`TransportListener`. The `ApciSession` validates the sequence number, advances V(R), and delivers the
contained `Asdu` to the client, which emits one `ClientEvent.AsduReceived` plus one
`ClientEvent.PointUpdated` per information object — serially, on the callback executor.

The single architectural rule that makes this clean: the `ByteBuf` exists only inside the `Serde`
classes and the transport pipeline. Above the transport boundary, everything is an immutable Java
object — `Apdu`, `Asdu`, `PointValue`, events — and below it, the `ApciSession` deals only in those
objects, never in sockets. The next document, [modules-and-dependencies.md](modules-and-dependencies.md),
makes that boundary precise.
