# Slipstream Protocol (Plan 1: identity, discovery, control channel)

This document is the single authority for what is implemented as of the Windows
Core plan (`docs/superpowers/plans/2026-08-25-core-discovery-control.md`). Plan 3's
Kotlin/Android implementation should conform to this, not to the C# source.

## 1. Ports

| Purpose | Port / Group | Transport |
|---|---|---|
| Discovery (multicast query/announce) | UDP `53320` | UDP, multicast group `224.0.0.167` |
| Control channel | TCP `53321` | TCP + TLS 1.2/1.3 |
| Bulk transfer | TCP `53322` | reserved, Plan 2 |
| Media | TCP `53323` | reserved, Plan 2 |

Protocol version is `1` (`SlipstreamPorts.ProtocolVersion`). The multicast group is
constrained to `224.0.0.0/24` because some Android devices reject groups outside
that range.

## 2. Identity (spec §4)

Each install generates, once, on first run:

- A random 16-byte device id, rendered as 32 lowercase hex characters.
- A self-signed ECDSA P-256 certificate (`CN=slipstream-<deviceId>`), valid for
  ~20 years. The certificate is not a CA and carries `DigitalSignature |
  KeyEncipherment` key usage.
- A **fingerprint**: SHA-256 of the DER-encoded certificate, lowercase hex
  (64 characters).

Trust is **fingerprint-pin only**. CA chain validation is disabled entirely
(`CertificateRevocationCheckMode = NoCheck`, and the validation callback ignores
chain errors) — a self-signed cert has no meaningful chain to validate. There is
exactly one paired peer at a time; re-pairing replaces it. An unpaired or
mismatched-fingerprint device gets nothing: no prompt, no override path, no
partial access.

## 3. Pairing code (spec §4)

A 6-digit decimal code, derived identically on both devices regardless of who
computes it first (order-independent):

```
a = fingerprintA.trim().toLowerInvariant()
b = fingerprintB.trim().toLowerInvariant()
(first, second) = sorted(a, b)   // ordinal string comparison
digest = SHA256(ASCII(first + second))
value  = big-endian uint32 of digest[0..4]
code   = (value mod 1_000_000), zero-padded to 6 digits
```

### Vectors (`protocol/vectors/pairing-codes.json`)

| a | b | code |
|---|---|---|
| `000...000` (68 zeros) | `fff...fff` (68 f's) | `048846` |
| `fff...fff` (68 f's) | `000...000` (68 zeros) | `048846` |
| `111...111` (68 1's) | `222...222` (68 2's) | `448183` |

The first two rows are the same pair with the arguments swapped — this is the
order-independence check.

## 4. TLS pinning rule (spec §4)

- Client (`PinnedTls.AuthenticateAsClientAsync`): presents its own certificate as
  the client certificate, `TargetHost = "slipstream"`, and accepts the server's
  certificate **only** if its SHA-256 fingerprint matches the paired peer
  (`PairedPeerStore.Trusts`).
- Server (`PinnedTls.AuthenticateAsServerAsync`): requires a client certificate
  (`ClientCertificateRequired = true`) but accepts *any* certificate at the TLS
  layer — `RemoteCertificateValidationCallback` unconditionally returns `true`.
  **The fingerprint check happens after the handshake completes**, in
  `ControlServer`: the accepted stream's peer fingerprint is compared against
  `PairedPeerStore.Trusts`, and any non-match is dropped immediately (stream and
  socket disposed, no message ever handed to application code). This
  post-handshake check is load-bearing — without it, the TLS layer alone would
  accept a connection from any device holding any self-signed certificate.

Both directions apply spec §11 layer 2 (RFC1918 / link-local / loopback only):
outbound connections refuse a non-local target address (`LanGuard.EnsureLocal`),
and inbound accepts refuse a non-local remote address before the TLS handshake
even starts. The server additionally binds only to the current local interface
address (§11 layer 1), never to `0.0.0.0`.

## 5. JSON-lines framing (spec §6)

One UTF-8 JSON object per line, `\n`-terminated (a trailing `\r` is tolerated
and stripped). Message shape:

```json
{"type": "hello", "id": "1", "payload": { }}
```

- `type` (string, required): message name.
- `id` (string, optional): present on requests and echoed on the matching
  response; absent on events.
- `payload` (object, optional): message-specific body.

Rules:

- **Line cap**: 1 MiB (`JsonLineCodec.MaxLineBytes = 1_048_576`). A line that
  would exceed this while *writing* throws before anything is sent. A line that
  exceeds this while *reading* throws and the connection is torn down — this is
  the one framing violation that is fatal, since the codec cannot resynchronize
  mid-line.
- **Skip-malformed**: any line that fails to parse as JSON, or parses but has no
  (or a blank) `type`, is silently discarded and reading continues with the next
  line. Empty lines are also skipped. This lets a peer running a newer protocol
  version send message types this peer doesn't understand yet without breaking
  the connection — the receiver's read loop keeps going, it just never surfaces
  that particular message.
- End of stream (peer closed the connection) is reported as `null` from
  `ReceiveAsync`, not an exception.

## 6. Discovery announcement (spec §5 S3)

Sent as a single UDP datagram to the multicast group, and as a unicast reply to
a query. Field names and ordering below are normative.

```json
{"v":1,"deviceId":"abc123","name":"Test PC","fingerprint":"deadbeef","control":53321,"kind":"announce"}
```

Fields: `v` (protocol version, must equal 1 or the message is discarded),
`deviceId`, `name` (display name), `fingerprint`, `control` (the sender's
control-channel TCP port, 1–65535), `kind` (`"announce"` or `"query"`).

A receiver discards the datagram (returns `null` from `TryParse`) if: JSON
parsing fails, `v` doesn't match the current protocol version, `deviceId` or
`fingerprint` is blank, or `control` is out of range. A receiver additionally
ignores announcements from itself (fingerprint match against its own identity),
from an untrusted fingerprint (not the paired peer), or from a non-local sender
address.

### Vectors (`protocol/vectors/announcements.json`)

| case | json |
|---|---|
| announce | `{"v":1,"deviceId":"abc123","name":"Test PC","fingerprint":"deadbeef","control":53321,"kind":"announce"}` |
| query | `{"v":1,"deviceId":"abc123","name":"Test PC","fingerprint":"deadbeef","control":53321,"kind":"query"}` |

## 7. Control messages: `hello` / `ping` families

These are the only message types implemented in Plan 1. Anything else received
is silently ignored per the skip-malformed / unknown-type rule (§5 above) — a
peer must never disconnect on an unrecognized `type`.

### `hello` (request) / `hello.ok` (response)

Exchanged once, right after the pinned TLS handshake, so each side can confirm
the other's protocol version, device id, display name, and fingerprint over the
authenticated channel (the fingerprint here is redundant with the TLS peer
certificate — it is a sanity check, not an additional trust decision).

Payload shape (`HelloPayload`): `{"version": int, "deviceId": string, "name":
string, "fingerprint": string}`.

```
--> {"type":"hello","id":"1","payload":{"version":1,"deviceId":"3ff30679fabe9b70581b49cce48bf9dc","name":"Client Phone","fingerprint":"63ecadfc1fe320f16dd69281b0ad8d42b81023089e8564054f02b721fe9fac33"}}
<-- {"type":"hello.ok","id":"1","payload":{"version":1,"deviceId":"ebbcecc2af9ca44fb0e58f8c2d4f3bfd","name":"Server PC","fingerprint":"adc7692d5f302e77d60c19740e4276c626839f2c82b1c875efb18589c74f9b98"}}
```

### `ping` (request) / `pong` (response)

Liveness check, no payload.

```
--> {"type":"ping","id":"2"}
<-- {"type":"pong","id":"2"}
```

### `pair.offer` / `pair.confirm` (out-of-band pairing)

Plan 1 pairs devices manually — an operator copies each device's id, display
name, and fingerprint (via the `Slipstream.Harness pair` command, or the
eventual UI) and calls `PairedPeerStore.Pair` on each side directly; both sides
then independently derive the same 6-digit `PairingCode` (§3) as a
human-verifiable confirmation that no one substituted a fingerprint in transit.

The wire messages below are reserved for a future *in-band* pairing flow (one
device proposes over an unauthenticated first contact, the other confirms) and
share the same payload shape as `hello`:

- `pair.offer` — payload `PairOfferPayload`: `{"version": int, "deviceId":
  string, "name": string, "fingerprint": string}`. Sent by the device
  initiating pairing before either side trusts the other's certificate, so it
  travels over a connection where TLS pinning cannot yet apply.
- `pair.confirm` — sent by the responder once the human has verified the
  pairing code out-of-band, to acknowledge the pairing is complete.

Plan 1 does not implement the network transport for these two message types;
only the payload record and the underlying `PairedPeerStore.Pair` /
`PairingCode.Derive` primitives that such a flow would use are implemented and
tested (Tasks 4–5, 16).

## 8. Out of scope here (specified in the design document, implemented in Plan 2)

- Bulk transfer (`list` / `stat` / `pull` / `push`) — spec §7, ports `53322`.
- Media server and thumbnails — spec §8–§9, port `53323`.
- `play` and `clipboard` message handlers — spec §6.
- Discovery re-run on network change (needs a live connection to tear down).

## 9. Layered network safety (spec §11)

1. **Bind to local interface**: the control server binds to the current local
   network's own address, never `0.0.0.0`.
2. **RFC1918 / link-local only**: every outbound connection target and every
   inbound accepted remote address is checked against `LanGuard.IsLocal` —
   `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16` (IPv4),
   loopback, IPv6 link-local (`fe80::/10`), and unique-local (`fc00::/7`).
   Anything else is refused before a socket operation is attempted, in both
   directions.
3. **Android `Network` binding** — Plan 3 scope, not implemented here.
4. **No third-party runtime dependencies** — the entire implementation uses only
   the .NET base class library (`System.*`).
