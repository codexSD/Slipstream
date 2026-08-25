# Slipstream Pairing Protocol (Plan 1b: pairing bootstrap)

This document is the single authority for the in-band pairing flow implemented
as of `docs/superpowers/plans/2026-08-25-pairing-bootstrap.md`. It supersedes
the "reserved for a future flow" note in `protocol/protocol.md` §7 — that
future flow is this one.

## 1. The pairing window

Pairing only ever happens inside a **120-second window** that a user opens
explicitly on each device (`PairingWindow`). Outside an open window, the
accept path is byte-for-byte identical to normal operation: an unpaired
inbound connection is dropped before a single message is read. The window is
the only thing that changes that, and it closes itself — on expiry, on a
successful pairing, or if the user cancels.

An already-paired peer is unaffected by the window and always reaches the
normal (`PeerConnected`) handler, never the restricted pairing handler, even
while a window happens to be open.

## 2. Restricted handler

A connection accepted through an open pairing window may exchange **only**
`pair.offer`, `pair.confirm`, and `pair.cancel`. Any other message type is
ignored (per the skip-malformed / unknown-type rule in `protocol.md` §5) and
must never reach the browse/transfer session. TLS still applies on this path
— the certificate is simply not pinned, since there is nothing to pin against
yet. Plaintext pairing is never permitted.

## 3. Wire flow

Both devices run the same state machine (`PairingSession` driven by
`PairingCoordinator`); only the initiator sends the first offer.

```
Initiator                              Responder
   |--- pair.offer (offer) ----------------->|
   |<-- pair.offer (offer) -------------------|
   |                                          |
   |   both derive the same 6-digit code from
   |   (localFingerprint, remoteFingerprint)
   |   and show it to their user
   |                                          |
   |--- pair.confirm ------------------------>|   (only if the user accepts)
   |<-- pair.confirm --------------------------|   (only if the user accepts)
   |                                          |
   |   both sides now hold two confirmations
   |   and persist the peer
```

If either user declines, that side sends `pair.cancel` instead of
`pair.confirm` and the attempt fails on both ends. A single-sided confirm
never pairs — persistence happens only once a device has both its own local
confirmation and the remote device's `pair.confirm`.

### Example JSON lines

```
--> {"type":"pair.offer","id":"1","payload":{"version":1,"deviceId":"3ff30679fabe9b70581b49cce48bf9dc","name":"Client Phone","fingerprint":"63ecadfc1fe320f16dd69281b0ad8d42b81023089e8564054f02b721fe9fac33"}}
<-- {"type":"pair.offer","id":"1","payload":{"version":1,"deviceId":"ebbcecc2af9ca44fb0e58f8c2d4f3bfd","name":"Server PC","fingerprint":"adc7692d5f302e77d60c19740e4276c626839f2c82b1c875efb18589c74f9b98"}}
--> {"type":"pair.confirm"}
<-- {"type":"pair.confirm"}
```

Decline instead of confirm:

```
--> {"type":"pair.cancel"}
```

## 4. Code derivation

Unchanged from `protocol.md` §3 — `PairingCode.Derive(fingerprintA,
fingerprintB)`, order-independent, vectored in
`protocol/vectors/pairing-codes.json`. The code is derived from the **TLS
certificate fingerprint**, never from the `fingerprint` field inside
`pair.offer` — the offer's payload is peer-supplied text and is not proof of
anything by itself; the certificate presented during the TLS handshake is the
only thing the handshake actually proves. `PairingSession.ReceiveOffer`
rejects an offer whose claimed fingerprint does not match the verified
certificate fingerprint before ever deriving a code.

No pairing secret crosses the wire at any point — both sides compute the code
independently and a human compares the two displayed numbers.

## 5. Threat model, stated explicitly

The six-digit code is `SHA-256(sorted(fpA) || sorted(fpB))` truncated to six
decimal digits. It **binds both certificates**. An attacker interposing on
the LAN presents their own certificate, which changes the derived code on at
least one side — so the two displayed codes disagree and the user declines.

To defeat this the attacker must find a certificate whose fingerprint yields
a six-digit collision against the victim pair: one chance in a million per
attempt, with attempts bounded by a 120-second window that a human opened
deliberately on both devices and is actively watching. For a personal
two-device LAN tool that is proportionate.

What this does **not** defend against: a user who confirms without comparing
the codes. The UI must show both codes prominently and say what the user is
being asked to check. That is a UI obligation, carried into the UI plans.

## 6. Discovery

While a pairing window is open, `PairingDiscovery` listens for peer
announcements (spec §5 S3, `protocol.md` §6) regardless of trust — an
unpaired peer's announcement is exactly what pairing needs to find. Outside
an open window, `PairingDiscovery.FindAsync` returns `null` immediately
without listening at all. Discovery still applies spec §11 LAN-only filtering
and never surfaces the local device's own announcement.
