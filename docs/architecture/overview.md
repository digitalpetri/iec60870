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
per-type `InformationObject` records in `com.digitalpetri.iec60870.asdu.object` are direct,
immutable representations of what travels on the wire, each with a co-located `Serde` that
encodes and decodes it. A caller who needs full control — conformance testing, private TypeIDs,
unusual cause/qualifier combinations — builds an `Asdu` and sends it with `Iec60870Client.send(Asdu)`,
and observes every inbound `Asdu` through the event stream. Nothing is hidden at this layer.

**The high-level facade** turns that wire model into a domain API. `Iec60870Client` offers
`interrogate`, `read`, `commands().single(...)`, and `synchronizeClock`; `Iec60870Server` hosts
`Station`s with `PointDefinition`s and answers requests from a current-value image. Values are
expressed as `PointValue<T>` with `Quality`, addressed by `PointAddress`, and delivered as typed
`ClientEvent`/`ServerEvent` records. This layer correlates request/response, manages the command
select-before-operate sequence, projects monitor objects onto domain point values, and serializes
event delivery onto a callback executor.

The escape hatch is always present: `Iec60870Client.send(Asdu)` / `events()` on the client, and
`ServerContext.send(Asdu)` / `ServerHandler.onRawAsdu(...)` on the server, let an application drop
to the raw layer for anything the facade does not model — without leaving the high-level API.

See [two-layer-api.md](two-layer-api.md) for the concrete types and short code sketches.

## Component map

```
       iec60870-tcp (assembly)  →  iec60870-transport-tcp  (Netty: Channel, EventLoopGroup, SslHandler)
                         ┌───────────────────────────────────────────────────────────────┐
                         │  TcpIec104Client.builder()        TcpIec104Server.builder()    │  ◄── iec60870-tcp
   user-facing  ───────► │  host/port/tls  ──► NettyClientTransport / NettyServerTransport │  ◄── iec60870-transport-tcp
   entry points          │  Iec104FrameDecoder (the whole-frame ByteBuf boundary)         │      (core-only octet transport)
                         │  builders delegate 104 assembly to Cs104Binding (in cs104)      │
                         └───────────────────────────────────────────────────────────────┘
            assembles {Session + transport}     returns the application interface
            and injects them into ───┐          (Iec60870Client / Iec60870Server)
                                     ▼
   ┌──────────────────────────────────────────────────────────────────────────────────────────┐
   │ iec60870-application  (NO Netty — speaks Asdu + the Session SPI only)                       │
   │   .client   Iec60870Client (+ DefaultIec60870Client), CommandService, Command, CommandResult,  │
   │             ClientEvent, InterrogationResult, ClientConfig                                 │
   │   .server   Iec60870Server (+ DefaultIec60870Server), Station, PointDefinition, StationRegistry,│
   │             ServerHandler, ServerContext, ServerEvent, ServerConfig, request/decision types│
   │   .point    PointValue<T>, PointType, Quality, PointCapability, TimeTagStyle, MonitorMapping │
   │   .catalog  PointCatalog, CatalogEntry, ObservedCatalog, ObservationMode, MergePolicy       │
   └──────────────────────────────────────────────────────────────────────────────────────────┘
   ┌──────────────────────────────────────────────────────────────────────────────────────────┐
   │ iec60870-cs104  (the 104 link/session engine; ByteBuf only inside the Apdu Serde)          │
   │   .cs104   ApciSession implements Session (V(S)/V(R), k/w, t1/t2/t3), Apdu, ControlField,   │
   │            UFunction, ApduFramer, ApciSettings, Cs104Binding                                │
   └──────────────────────────────────────────────────────────────────────────────────────────┘
   ┌──────────────────────────────────────────────────────────────────────────────────────────┐
   │ iec60870-cs101  (the 101 FT1.2 link layer; ByteBuf only inside the Ft12 Serde)              │
   │   .cs101   Ft12LinkLayer implements Session (FCB stop-and-wait, balanced/unbalanced),       │
   │            Ft12Frame, LinkControlField, Ft12Framer, LinkSettings, Cs101Binding              │
   └──────────────────────────────────────────────────────────────────────────────────────────┘
        iec60870-application, iec60870-cs104, and iec60870-cs101 all depend on core ──┐ (Session SPI + raw model)
                                                                                      ▼
   ┌──────────────────────────────────────────────────────────────────────────────────────────┐
   │ iec60870-core  (no Netty channel/handler types; ByteBuf only inside .asdu Serde + SPI)     │
   │                                                                                            │
   │  SESSION SPI                                                                                │
   │   .session  Session (Asdu-shaped SPI: sendAsdu, start/stopDataTransfer, Events)             │
   │                                                                                            │
   │  RAW PROTOCOL MODEL + CODECS                                                                │
   │   .asdu          Asdu, AsduType, Cause, InformationObject, InformationObjectCodec(s)         │
   │   .asdu.object   one record per supported TypeID (SinglePointInformation, ... )            │
   │   .asdu.element  reusable elements/qualifiers (Qds, Vti, NormalizedValue, QualifierOf*, ...) │
   │   .asdu.time     Cp56Time2a, Cp24Time2a, Cp16Time2a                                         │
   │   .address       CommonAddress, InformationObjectAddress, OriginatorAddress, PointAddress   │
   │                                                                                            │
   │  CONFIG + ERRORS (package root)                                                             │
   │   ProtocolProfile, SessionSettings, OutboundQueuePolicy, TlsOptions                         │
   │   Iec60870Exception (+ AsduDecodeException, ProtocolTimeoutException, ConnectionClosed-,       │
   │                    NegativeConfirmation-, UnsupportedAsduType-, SequenceNumberException)     │
   │                                                                                            │
   │  TRANSPORT INTERFACES (no Netty channel/handler; whole-frame ByteBuf)                       │
   │   .transport  ClientTransport, ServerTransport, ServerTransportConnection, TransportListener │
   └──────────────────────────────────────────────────────────────────────────────────────────┘
```

The diagram shows the 104/TCP stack. The 101 serial profile slots two peer modules in beneath the
same `iec60870-application` facade: `iec60870-cs101` (the `Ft12LinkLayer` shown above, a `Session`
peer of `ApciSession`) and `iec60870-transport-serial` (`SerialClientTransport` /
`SerialServerTransport`, a core-only octet-transport peer of the Netty transport, reached through the
`SerialIec101Client` / `SerialIec101Server` builders in `iec60870-serial`). The optional
`TcpIec101Client` / `TcpIec101Server` builders (in `iec60870-tcp`) run that same FT1.2 link layer
over the Netty transport instead.

### How a message moves through the stack

Outbound (client issuing a command): the facade (`CommandService`) builds a domain `Command`, maps
it to a wire `Asdu`, and hands it to its injected `Session` via `sendAsdu(...)`. For a 104 stack the
session is an `ApciSession`, which wraps the `Asdu` in an I-format `Apdu` honoring the `k` window;
the session's `Output` (wired in the `Tcp*` builder) runs `ApduFramer.encode(...)` into a Netty
`ByteBuf` and writes it through the octet transport.

Inbound (a spontaneous measurement): the transport's `Iec104FrameDecoder` frames on the `0x68`
start/length and delivers one whole-frame `ByteBuf` through the `TransportListener`. The builder's
inbound glue runs `ApduFramer.decode(...)` to produce an `Apdu` and feeds it to `ApciSession.onApdu`.
The session validates the sequence number, advances V(R), and delivers the contained `Asdu` to the
facade through `Session.Events.onAsdu`, which emits one `ClientEvent.AsduReceived` plus one
`ClientEvent.PointUpdated` per information object — serially, on the callback executor.

The 101 serial profile moves a message the same way, with one substitution: the link layer. IEC
60870-5-101 is the serial profile of the same standard — 104 is its TCP profile — so the `Asdu` and
the whole `iec60870-application` facade above it are identical. Only the layer between the `Session`
SPI and the wire differs: in place of the `ApciSession` + `Apdu` framing, an `Ft12LinkLayer` (in
`iec60870-cs101`) wraps the `Asdu` in an FT1.2 frame under stop-and-wait FCB flow control, and a
`SerialClientTransport` / `SerialServerTransport` (in `iec60870-transport-serial`) carries the framed
`ByteBuf` over a serial port instead of a Netty channel. The `SerialIec101Client` /
`SerialIec101Server` builders assemble that stack through `Cs101Binding` exactly as the `Tcp*`
builders assemble the 104 stack through `Cs104Binding`; the optional `TcpIec101Client` /
`TcpIec101Server` run the same FT1.2 link layer over the Netty transport. See
[ft12-link-layer.md](ft12-link-layer.md) for the link-layer detail.

The single architectural rule that makes this clean: the `ByteBuf` exists only inside the `Serde`
classes and the transport pipeline. Above the transport boundary, everything is an immutable Java
object — `Apdu`, `Asdu`, `PointValue`, events — and below it, the `ApciSession` deals only in those
objects, never in sockets. The next document, [modules-and-dependencies.md](modules-and-dependencies.md),
makes that boundary precise.
