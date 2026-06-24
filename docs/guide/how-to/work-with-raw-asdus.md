# Work with raw ASDUs

The library has [two layers](../../architecture/two-layer-api.md): a high-level facade that reads
points and issues standard commands for you, and a lower **raw protocol layer** that exposes the exact
bytes on the wire. This page is about that lower layer. You drop to it for one of two reasons: you need
exact wire control of a TypeID the facade *does* model — for conformance testing or debugging — or you
need to send and receive a TypeID the facade does **not** act on, such as the file-transfer `F_*` types
or a private procedure. The escape hatch lives on the high-level
[`Iec104Client`](../../architecture/two-layer-api.md) and `Iec104Server`, so you never give up your
connection to use it.

If you only want to read points and issue ordinary single, double, set-point, or interrogation
commands, you are on the wrong page: see [Connect & interrogate](./connect-and-interrogate.md) and
[Send commands](./send-commands.md). Most users never touch the raw layer.

## When you need the raw layer

| Use the raw layer when… | Stay on the facade when… |
|---|---|
| You are writing a conformance or exact-wire test and must control every octet. | You are doing standard reads, commands, or [interrogation](./connect-and-interrogate.md). |
| A **supported** TypeID is delivered but the facade does not act on it, and you want to inspect it. | You want clock sync, general interrogation, or counter interrogation — those are higher-level and safer. |
| You must speak a private TypeID or an `F_*` file-transfer procedure the facade does not model. | The standard helpers already cover your case. |

For the long-form version of this decision, see the
[Choosing a layer](../../architecture/two-layer-api.md#choosing-a-layer) table in the architecture
docs.

## The raw types at a glance

The raw layer maps IEC 104 jargon onto a small set of concrete Java types, all under
`com.digitalpetri.iec104`:

| IEC 104 term | Java type | Where it lives |
|---|---|---|
| [ASDU](../reference/glossary.md) (Application Service Data Unit) | [`Asdu`](../reference/glossary.md) | `asdu` |
| [Type identification / TypeID](../reference/glossary.md) | [`AsduType`](../reference/glossary.md) (enum) | `asdu` |
| [Cause of transmission (COT)](../reference/glossary.md) | [`Cause`](../reference/glossary.md) (enum) | `asdu` |
| P/N bit and T bit | `negative` and `test` fields **on `Asdu`** (not inside `Cause`) | `asdu` |
| The information objects | [`InformationObject`](../reference/glossary.md) records, one per supported TypeID | `asdu.object` |
| [Common Address (CA)](../reference/glossary.md) | [`CommonAddress`](../reference/glossary.md) | `address` |
| [Information Object Address (IOA)](../reference/glossary.md) | [`InformationObjectAddress`](../reference/glossary.md) | `address` |
| [Originator Address (OA)](../reference/glossary.md) | [`OriginatorAddress`](../reference/glossary.md) | `address` |
| [Quality descriptor (QDS)](../reference/glossary.md), qualifiers, … | `Qds`, `QualifierOfInterrogation`, … | `asdu.element` |
| Time tags | `Cp16Time2a`, `Cp24Time2a`, `Cp56Time2a` | `asdu.time` |

Every wire type nests a `public static final class Serde` that encodes and decodes it; the one you call
yourself is [`Asdu.Serde`](../../architecture/two-layer-api.md#co-located-serde), which frames the whole
data unit and delegates each object body to its record's own `Serde`. There is no separate `codec`
package — the codecs are co-located with the model types. For the full TypeID → record table, see the
[coverage matrix](../reference/coverage-matrix.md); this section is a distilled view of the architecture
doc's [raw protocol layer](../../architecture/two-layer-api.md#the-raw-protocol-layer).

## Build an Asdu by hand

An `Asdu` is a record with eight components, in this order:

1. `type` — the [`AsduType`](../reference/glossary.md) (type identification) shared by every object.
2. `sequence` — the [SQ bit](../reference/glossary.md): `false` means each object carries its own IOA;
   `true` means one IOA followed by consecutive objects.
3. `cause` — the [`Cause`](../reference/glossary.md) (cause of transmission).
4. `negative` — the P/N bit; `false` is a positive (normal) ASDU.
5. `test` — the T bit; `false` means not a test ASDU.
6. `originatorAddress` — the [`OriginatorAddress`](../reference/glossary.md); use
   `OriginatorAddress.none()` when you do not track originators. It is written on the wire only when the
   profile's cause-of-transmission length is 2 (see the next section).
7. `commonAddress` — the [`CommonAddress`](../reference/glossary.md) (the station).
8. `objects` — the list of [`InformationObject`](../reference/glossary.md) records. The compact
   constructor validates `objects.size() <= 127` and throws `IllegalArgumentException` otherwise.

The snippet below builds a `C_IC_NA_1` station interrogation in the [control direction](../reference/glossary.md),
exactly as `RawAsduExample` does. `InterrogationCommand` is the `asdu.object` record for that TypeID;
[Choosing a point type](../reference/choosing-a-point-type.md) helps you pick the right record for other
TypeIDs.

```java
import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.address.OriginatorAddress;
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec104.asdu.object.InterrogationCommand;
import java.util.List;

// C_IC_NA_1 station (global) interrogation for common address 1.
InterrogationCommand object =
    new InterrogationCommand(InformationObjectAddress.of(0), QualifierOfInterrogation.STATION);

Asdu asdu = new Asdu(
    AsduType.C_IC_NA_1,        // type identification
    false,                     // sequence (SQ): false = each object carries its own IOA
    Cause.ACTIVATION,          // cause of transmission
    false,                     // negative (P/N bit): false = positive
    false,                     // test (T bit)
    OriginatorAddress.none(),  // originator address (on the wire only when cotLength == 2)
    CommonAddress.of(1),       // common address (station)
    List.of(object));          // information objects (<= 127)
```

## Encode and decode it

`Asdu.Serde` is the entry point. Both `encode` and `decode` take a
[`ProtocolProfile`](../../architecture/two-layer-api.md), which selects the field widths used on the
wire. `ProtocolProfile.iec104Default()` is the standard IEC 104 profile: a 2-octet cause of
transmission (so the originator address is present), a 2-octet common address, and a 3-octet
information object address. For non-default widths, see [Tune the APCI session](./tune-apci.md).

```java
import com.digitalpetri.iec104.ProtocolProfile;
import com.digitalpetri.iec104.asdu.Asdu;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

ProtocolProfile profile = ProtocolProfile.iec104Default(); // 2-octet COT, 2-octet CA, 3-octet IOA

ByteBuf buffer = Unpooled.buffer();
try {
  Asdu.Serde.encode(asdu, profile, buffer);                // writes; never releases
  System.out.println("encoded: " + ByteBufUtil.hexDump(buffer));

  Asdu decoded = Asdu.Serde.decode(profile, buffer);       // reads; never releases
  System.out.println("decoded: " + decoded);
} finally {
  buffer.release();                                         // the caller allocates and releases
}
```

These two-argument overloads use the standard codec registry internally, so they handle every supported
TypeID. There are also explicit-registry overloads
(`encode(asdu, profile, registry, buffer)` / `decode(profile, registry, buffer)`), but you almost never
need them — the standard registry is what the facade dispatches against, so a custom registry buys you
nothing at the facade level. Stick to the two-argument calls.

Decode rejects malformed or out-of-range wire data with `AsduDecodeException` (a truncated header, an
undefined cause, a count outside `0..127`, an IOA overflow under sequence addressing) and rejects an
undefined or uncodec'd type identification with `UnsupportedAsduTypeException`. See the
[error model](../reference/errors.md) for the full list.

## Who owns the ByteBuf

There is one rule, and it never changes: **the codec never allocates and never releases the buffer; the
caller owns it.** So when you call `Asdu.Serde` yourself, you allocate the `ByteBuf` and you
`release()` it — in a `try`/`finally`, exactly as the snippet above and `RawAsduExample` do.

Through the high-level facade you never see a `ByteBuf` at all: the transport allocates the buffer to
read from the socket, hands you decoded `Asdu` objects, and releases the buffer afterward. The raw
`Asdu` you receive in an event or hook is plain data with no buffer attached, so there is nothing for
you to release. For the complete ownership and threading model, see
[Buffer ownership](../../architecture/buffers-and-threading.md#buffer-ownership); it is not repeated
here.

## Send a raw ASDU from the client

On an open, started connection, `Iec104Client.send(Asdu)` transmits any ASDU as-is, bypassing the
high-level command and request helpers. This is the client-side escape hatch for private TypeIDs and
conformance work.

```java
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.client.Iec104Client;

// client is an open, started Iec104Client (see Getting Started).
client.send(asdu);                 // fire-and-forget; throws ConnectionClosedException if closed
// or, non-blocking:
// client.sendAsync(asdu).thenRun(() -> ...);
```

`send` completes once the frame has been written — **not** when a reply arrives. Any response comes back
asynchronously through `events()` (see the next section). If the connection is closed, `send` throws
`ConnectionClosedException`; `sendAsync` completes its stage exceptionally instead. "Started" means a
[STARTDT](../reference/glossary.md) handshake has put the link into data-transfer state; see
[Getting Started](../getting-started.md).

## Receive a raw ASDU on the client

Every inbound ASDU is published as `ClientEvent.AsduReceived(asdu)`, in addition to any
`PointUpdated` events derived from its monitor objects. That means a TypeID the facade does not act on
is still observable — it is never silently dropped. Match `AsduReceived` in your subscriber's `onNext`:

```java
import com.digitalpetri.iec104.client.ClientEvent;

// inside Flow.Subscriber<ClientEvent>#onNext(ClientEvent event):
switch (event) {
  case ClientEvent.AsduReceived a -> inspect(a.asdu());  // every inbound ASDU, modeled or not
  default -> {}
}
```

Events are delivered serially on the client's callback executor — a subscriber never observes two events
concurrently. For the full event surface and the threading rules, see
[Handle events](./handle-events.md) and
[Threading and callback serialization](../../architecture/buffers-and-threading.md#threading-and-callback-serialization);
this page does not re-explain them.

## Handle a raw ASDU on the server

The server side mirrors the client. Override `ServerHandler.onRawAsdu(ServerContext, Asdu)` — it is
offered every received ASDU **before** the standard dispatch. Return `true` to claim the ASDU: the
standard dispatch is then skipped and you own the reply, which you send with `ServerContext.send(Asdu)`.
Return `false` (the default) to defer to standard handling. For a non-blocking reply, override
`onRawAsduAsync` instead. The server also emits `ServerEvent.AsduReceived(remoteAddress, asdu)` for pure
observation.

```java
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.server.ServerContext;
import com.digitalpetri.iec104.server.ServerHandler;

ServerHandler handler = new ServerHandler() {
  @Override
  public boolean onRawAsdu(ServerContext context, Asdu asdu) {
    if (asdu.type() == AsduType.F_FR_NA_1) {   // a private/bespoke procedure
      // ...build a reply Asdu...
      context.send(reply);
      return true;                             // claim it: standard dispatch is skipped
    }
    return false;                              // defer to standard handling (the default)
  }
};
```

This is the hook for private TypeIDs and bespoke procedures — for example, an `F_*` file-transfer state
machine. Note the snippet only branches on `asdu.type()`, which is a frame-level decision and always
safe. The `F_FR_NA_1` body itself **cannot** be decoded by the standard registry (see the next section),
so a real implementation parses the element bytes itself rather than expecting a decoded record. For
where `ServerHandler` and `ServerContext` come from, see [Host a server](./host-a-server.md); for the
rationale behind these hooks, see
[Raw send/receive hooks](../../architecture/errors-and-extensibility.md#raw-sendreceive-hooks).

## What the raw layer can and can't do

There is **no pluggable codec registry** at the facade level. Dispatch is driven by `AsduType`, and
`AsduType.fromId` rejects an undefined type identification with `UnsupportedAsduTypeException` before any
codec runs. The consequences are sharp:

- A **modeled** TypeID round-trips fully: you can build it, encode it, and decode its object bodies
  back. (Everything in the [coverage matrix](../reference/coverage-matrix.md) is modeled.)
- A **supported-but-not-acted-on** TypeID is still delivered raw — via `ClientEvent.AsduReceived` or
  `ServerHandler.onRawAsdu` — and never silently dropped.
- An **unsupported** TypeID that still has an `AsduType` constant (the `F_*` file-transfer types) can be
  carried as a raw `Asdu` envelope, but **decoding its element bodies fails**: the standard registry has
  no codec for it, so `Asdu.Serde.decode` throws `UnsupportedAsduTypeException`. Handle those at the
  frame level (branch on `asdu.type()`) or parse the bytes yourself.
- A **private-range** TypeID (128–255) has no `AsduType` constant at all; `AsduType.fromId` rejects it
  with `UnsupportedAsduTypeException`.

See the [coverage matrix](../reference/coverage-matrix.md) for what is modeled and the
[error model](../reference/errors.md) for what each operation throws.

## The runnable example

[`RawAsduExample`](../../../iec104-examples/src/main/java/com/digitalpetri/iec104/examples/RawAsduExample.java)
is the shipped end-to-end demonstration of everything above, and it needs no peer. It builds a
`C_IC_NA_1` station interrogation and an `M_ME_NB_1` scaled measured value, encodes each to bytes with
`Asdu.Serde`, prints the hex, decodes it back, and prints the round-tripped object. Run it with:

```bash
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.RawAsduExample
```

It prints four labeled lines per ASDU — `built`, `encoded` (hex), `decoded`, and `objects`. The
interrogation block looks like this:

```
C_IC_NA_1 station interrogation
  built:   Asdu[type=C_IC_NA_1, sequence=false, cause=ACTIVATION, ...]
  encoded: 64010600010000000014
  decoded: Asdu[type=C_IC_NA_1, sequence=false, cause=ACTIVATION, ...]
  objects: [InterrogationCommand[address=InformationObjectAddress[value=0], ...]]
```

The first octet `64` is the TypeID (100 = `C_IC_NA_1`) and the trailing `14` is the qualifier
(20 = station). See the [examples README](../../../iec104-examples/README.md) for the full set of
runnable examples.

## See also

- [Two-layer API](../../architecture/two-layer-api.md) — how the raw and high-level layers relate.
- [Errors and extensibility](../../architecture/errors-and-extensibility.md) — the rationale for the raw
  send/receive hooks and why there is no pluggable registry.
- [Buffers and threading](../../architecture/buffers-and-threading.md) — the full `ByteBuf` ownership and
  callback-serialization model.
- [Coverage matrix](../reference/coverage-matrix.md) — which TypeIDs are modeled versus raw-only.
- [Glossary](../reference/glossary.md) — IEC 104 vocabulary mapped to Java types.
- [Error model](../reference/errors.md) — what encode, decode, and send throw.
- [Send commands](./send-commands.md) and [Handle events](./handle-events.md) — the facade you probably
  want instead.
- [Host a server](./host-a-server.md) — where `ServerHandler` and `ServerContext` are introduced.
