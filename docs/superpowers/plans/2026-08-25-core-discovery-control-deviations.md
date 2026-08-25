# Deviations from Plan 1 (core discovery & control)

**Date:** 2026-08-25
**Plan:** [`2026-08-25-core-discovery-control.md`](2026-08-25-core-discovery-control.md)
**Executed via:** subagent-driven development, 16 tasks + one final-review fix wave.

Everything below is a place where the delivered code differs from the plan's verbatim text, or
where the plan's own scope left a gap that surfaced during implementation. Each entry states what
changed, why, and whether it's closed or carried forward.

## Fixed during the final whole-branch review

### MulticastStrategy shared-socket read race (Critical, fixed)

The plan's Task 11 code used one `UdpClient` with two independent long-lived receive loops
(`FindAsync`, run per discovery attempt; `RespondToQueriesAsync`, run for the app's lifetime).
Both called `ReceiveAsync` concurrently on the same socket. .NET delivers each inbound datagram to
exactly one pending `ReceiveAsync`, nondeterministically — so a query meant for the responder
could be stolen by an in-progress `FindAsync` (breaking spec §5 S3's unicast-reply fallback for
asymmetric-multicast networks), or an announce meant for `FindAsync` could be stolen by the
responder loop (stalling discovery). No single task's isolated tests could catch this — it only
appears once both loops run concurrently on one instance, the real production shape.

**Fix:** redesigned to one reader loop that parses each datagram once and fans it out to both
concerns — the responder path unconditionally, and any active `FindAsync` callers via per-call
`Channel<>` subscriptions. A new regression test exercises both paths concurrently on one
instance and asserts neither steals the other's datagram.

## Ruled during task review (kept as implemented, not fixed)

### LanGuard accepts IPv6 ULA (fc00::/7) in addition to RFC1918/link-local

Verbatim plan code. The spec's LAN-only table (§11) lists RFC1918 + link-local explicitly and
doesn't mention IPv6 ULA. Ruling: ULA is non-routable/local by the same class of guarantee as
RFC1918, so accepting it doesn't weaken the LAN-only enforcement — it only widens what counts as
local, never narrows what's rejected. Kept as-is.

### PairedPeerStore only degrades to unpaired on JSON corruption, not on I/O errors

Verbatim plan code and comment ("a corrupt store means unpaired, not a crash") — scoped
specifically to JSON parse failures. A disk/permission failure while reading the store file will
propagate rather than silently unpair. Kept as-is: swallowing all I/O errors would mask real disk
problems the plan doesn't ask to hide.

### PeerAnnouncement.TryParse doesn't validate DisplayName or Kind

Verbatim plan code. Only Version, DeviceId, Fingerprint, and ControlPort are validated — the
fields with security/routing consequences. A missing DisplayName renders blank in the UI; a
missing Kind defaults to Announce. Neither has a security or protocol-correctness impact once the
fingerprint-trust check (downstream, in MulticastStrategy) has already gated the datagram. Kept
as-is.

### PinnedTls registers the certificate-validation callback once, not twice

The plan's given code sets `RemoteCertificateValidationCallback` both on the `SslStream`
constructor and again on `SslClientAuthenticationOptions`/`SslServerAuthenticationOptions`. On
.NET 9 this throws `InvalidOperationException` (double registration is no longer permitted).
Fixed by keeping only the options-level callback, verified to carry the identical fingerprint-pin
logic and to be the actual object `AuthenticateAsClientAsync`/`AuthenticateAsServerAsync`
consumes. Confirmed functionally equivalent, not a security weakening — required by the runtime,
not a shortcut.

## Scope gaps carried forward, not closed in this branch

### `protocol.md` lives at `protocol/protocol.md`, not `docs/protocol.md` — CLOSED

Closed by [`2026-08-25-pairing-bootstrap.md`](2026-08-25-pairing-bootstrap.md) (Plan 1b), Task 7.
Spec §18's repository-structure table now points at `protocol/protocol.md`, matching the delivered
layout, and lists `protocol/pairing.md` alongside it.

### No code path yet bootstraps a first pairing — CLOSED

Closed by [`2026-08-25-pairing-bootstrap.md`](2026-08-25-pairing-bootstrap.md) (Plan 1b). A
time-boxed pairing window (`PairingWindow`), a restricted accept path on `ControlServer`, an
unpinned pairing connect (`ControlClient.ConnectForPairingAsync`), unpaired-peer discovery
(`PairingDiscovery`), and a mutual-confirmation state machine (`PairingSession` /
`PairingCoordinator`) now let two never-paired devices find each other, derive and compare a
six-digit code, and reach a persisted trusted state on both sides. See
[`protocol/pairing.md`](../../../protocol/pairing.md) for the wire spec.

### Cross-machine discovery-matrix verification not performed

Plan Task 16's Step 8 calls for running the console harness across a real phone-hotspot /
external-WiFi / PC-hotspot matrix. Not possible in this single-machine sandbox. Each strategy and
the accept-side security path are covered independently by loopback unit/integration tests, but
real-hardware verification of the matrix is still outstanding before this is field-proven.
