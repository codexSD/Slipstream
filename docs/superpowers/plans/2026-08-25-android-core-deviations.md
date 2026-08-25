# Deviations from Plan 3 (Android core)

**Date:** 2026-08-26
**Plan:** [`2026-08-25-android-core.md`](2026-08-25-android-core.md)
**Executed via:** subagent-driven development, 12 tasks + a hardware-testing fix discovered during Task 13.

Everything below is a place where the delivered code differs from the plan's verbatim text or the
design spec's stated behavior, or where the plan's own scope left a gap that surfaced during
implementation or real-hardware verification. Each entry states what changed, why, and whether
it's closed or carried forward.

## Fixed during real-hardware testing (Task 13)

### AndroidNetworkInfo bound listening sockets to the wrong interface when hosting a hotspot (Critical, fixed)

Design spec §5's primary scenario — "the phone is the PC's default gateway" — was broken on real
hardware. `AndroidNetworkInfo.current()` used `ConnectivityManager.activeNetwork` to pick the bind
address for `ControlServer`/`BulkServer`/`MediaServer`. On a real phone hosting a WiFi hotspot,
`activeNetwork` reflects the device's own default-route network (its cellular/uplink connection),
not the interface it's hosting the AP on — modern Android doesn't surface a device's own
tethering/AP interface via `ConnectivityManager` as an "active network" for the hosting device at
all. Verified concretely: `adb shell netstat` showed the servers listening on `10.255.81.x` (the
phone's cellular side) while the actual hotspot interface (`wlan1`, `10.199.176.137/24`) — the one
a real PC connects through — was untouched. A real PC's `Test-NetConnection` to
`10.199.176.137:53321` failed before the fix.

**Fix:** `AndroidNetworkInfo.current()` now enumerates live `NetworkInterface`s directly (filtered
to up, non-loopback, `LanGuard`-local addresses, ranked by a `wlan/ap` > `eth/usb` > other >
`rmnet/ccmni/pdp` name-priority heuristic when more than one qualifies), falling back to the old
`ConnectivityManager` path only when enumeration yields nothing. Verified end-to-end on the real
hardware that exposed the bug: the server now binds `10.199.176.137`, and the same
`Test-NetConnection` now succeeds. Commit `0f7c662`.

Two narrower follow-ups from this fix's own review, not closed in this branch:

- **STA+AP concurrency has no deterministic tie-break.** When a device is simultaneously a WiFi
  client and hosting a hotspot, both interfaces tie at the top priority and the winner is
  enumeration order (stable within a boot, but unproven which one wins). No test covers this case.
  If hit, it can silently reproduce the original symptom for that specific configuration.
- **`SlipstreamPeer.onNetworkChanged`'s `current() != null` readiness gate was weakened.** The gate
  used to track whether the *specific* network `ConnectivityManager` had just reported was ready;
  now `current()` can return non-null off an unrelated interface while the newly-reported network's
  own interface is still coming up, so servers can start bound to a stale/wrong address without the
  dedup logic (Task 12) triggering a corrective retry.

### Cross-implementation pairing and discovery not fully verified — OPEN

Task 13's Step 1 ("open the pairing window on the phone") could not be performed as written: this
plan's scope explicitly excludes UI (Plan 4 consumes this module), so the shipped app has no
pairing UI. Both sides were paired out-of-band instead — the same mechanism Plan 1's manual `pair`
harness command uses: each device's real identity (device id + TLS certificate fingerprint) was
extracted (the phone's via a throwaway mutual-TLS `openssl s_client` probe against its own running
`ControlServer`, since the protocol accepts any client certificate at the raw TLS layer per
protocol.md §4) and written directly into both sides' `PairedPeerStore` JSON. Pairing itself
persisted correctly on both ends.

Full discovery (`find`, C# harness) against the real, now-correctly-bound, now-paired phone still
failed. A targeted raw `System.Net.Sockets.TcpClient` connect to the same host:port succeeded
instantly, isolating the failure to the TLS handshake stage specifically: **.NET's `SslStream`
client (`PinnedTls.AuthenticateAsClientAsync`) against Android's server never completes within its
timeout budget, while a manual mutual-TLS handshake via `openssl s_client` against the exact same
server succeeds and returns a valid certificate.** Forcing TLS 1.2-only on the .NET client (a
throwaway local test, reverted, not committed) did not resolve it. Root cause not identified within
the time available — candidates include cipher-suite negotiation, ALPN, or session-resumption
differences between .NET's SslStream and Android's JSSE/Conscrypt stack. This needs focused
TLS-interop debugging (a packet capture of the actual handshake is the natural next step) in a
follow-up session before the "phone hotspot, PC joins" matrix row can be called verified.

## Matrix rows from Task 13, Step 2 — what was and wasn't verified

| Check | Result |
|---|---|
| Phone hotspot, PC joins — PC finds the phone via gateway-probe | **Partially verified.** Real topology confirmed (PC's gateway = phone's hotspot IP). Bind-address bug found and fixed on real hardware. Full discovery handshake still fails for the TLS-interop reason above — **NOT verified end-to-end**. |
| Both on external WiFi — multicast or subnet-sweep wins | **NOT verified** — no second external-WiFi network was available in this session; only the phone-hotspot topology was accessible. |
| Repeat on the same network — cached-endpoint wins | **NOT verified** — depends on the row above succeeding first. |
| PC pulls / phone pulls a 1 GB file, hashes match | **NOT verified** — blocked on the same TLS-interop gap; no control channel ever completed, so no bulk transfer was attempted. |
| Kill WiFi mid-transfer, restore, resumes without restart | **NOT verified** — same block. |
| Phone picks a video → PC plays it | **NOT verified** — no phone-side UI to pick a file exists yet (out of scope, Plan 4). |
| PC browses the phone's DCIM, thumbnails/durations appear | **NOT verified** — blocked on control channel. |
| Clipboard both directions | **NOT verified** — blocked on control channel. |
| Airplane mode with cellular on — no traffic leaves the device | **NOT verified** — not attempted; would need packet capture tooling not set up in this session. |
| Throughput (hotspot vs. router) | **NOT recorded** — no completed transfer to measure. |

What **was** independently verified on the real device, outside the matrix's own checklist:

- Debug APK builds and installs cleanly on real hardware (`ALI-NX1`, Android/Magic OS, target SDK 35).
- The app launches without crashing.
- The foreground service starts and reports `isForeground=true` with the `connectedDevice`
  foreground-service type — the exact type Task 12's BootReceiver fix round switched to, confirmed
  correct on a real device (not just Robolectric).
- The AP-binding bug above, found and fixed via genuine field testing — exactly the class of
  defect this task exists to catch, and one no loopback/unit test could have surfaced.
- Post-fix, raw TCP reachability from a real PC to the real phone's control port on the real
  hotspot network.

## Ruled during task review (kept as implemented, not fixed)

### Media tokens are multi-use, not single-use, contradicting design.md §8's literal text

design.md §8 states media/thumbnail URLs "carry a single-use, time-limited token." The Task 11
implementation makes them explicitly multi-use within their 12-hour-or-restart lifetime, because
HTTP Range requests for seeking issue many separate GETs against the same URL — a genuinely
single-use token cannot survive normal video seeking. Ruling: keep the multi-use implementation;
the design doc's "single-use" line didn't account for HTTP Range semantics. This does not weaken
the bulk transfer path's token model (Task 9), which is a separate, deliberately multi-use-within-
5-minutes design already documented in the plan itself.

### Bulk tokens are multi-use within a 5-minute expiry, not single-use

The design doc's general prose (§7) says the bulk path is "authenticated by a single-use token" in
places, but Task 9's plan brief is explicit and is the pre-flight-confirmed authority here: tokens
are use-unlimited within a 5-minute expiry, scoped to one transfer id and one path (the latter half
enforced after a task-review fix, see below). A genuinely single-use token would break resumed
downloads needing the same token across multiple stream connections — this matches a documented C#
Plan-2b ruling on the Windows side, and the two implementations agree.

## Fixed during task review (this branch's own fix loops)

Selected highlights — the full per-round history lives in this plan's now-deleted SDD workspace
ledger; git history carries every commit.

- **Task 3:** a third-party crypto dependency (BouncyCastle) was added for self-signed certificate
  generation, violating the plan's "platform APIs only" constraint; replaced with a hand-rolled
  minimal DER/X.509 encoder on pure JDK APIs. Separately, `DeviceIdentity.load()` returned a
  hardcoded placeholder for `deviceId` on every reload instead of the real persisted value — fixed
  by persisting the device id in a sidecar file alongside the PKCS#12 keystore.
- **Task 5 (discovery):** `MulticastStrategy.start()` could leak a `WifiManager.MulticastLock`
  reference if `transportFactory()` threw (e.g. a real device where multicast is transiently
  restricted) — the lock was acquired before the failure point and never released, and a repeated
  failure would compound. Fixed to release the lock on any failure inside the critical section.
- **Task 6 (control channel):** the 1 MiB oversized-line read cap threw the documented fatal
  exception but didn't guarantee socket teardown at the connection layer — it only appeared to
  close by accident of `ControlServer`'s enclosing try/catch in the original tests. Fixed to make
  teardown unconditional in `ControlConnection.receive()` itself.
- **Task 7 (pairing):** the TLS-handshake-verified fingerprint `PairingCoordinator` needs (never
  the wire payload's claimed one — the whole point of the pairing security model) was computed by
  `ControlServer` and then discarded before reaching the pairing callback, leaving no sanctioned way
  for later wiring to obtain it correctly. Fixed by exposing `verifiedFingerprint` on
  `ControlConnection`, populated at construction.
- **Task 9 (bulk transfer):** `BulkToken.sourcePath` was stored but never enforced — the "scoped to
  one transfer id AND one path" guarantee was half-implemented. Fixed by having `BulkServer`
  compare the token's path against the resolved file's path and silently refuse on mismatch.
- **Task 12 (session/peer/service — the highest-risk task, two fix rounds):**
  - `BulkServer`/`MediaServer` bound the wildcard address with no `LanGuard` filtering on accepted
    connections, while `ControlServer` correctly bound the network-specific local address — spec
    §11 layers 1 and 2 were only implemented for one of the three servers. Fixed to match
    `ControlServer`'s pattern on all three.
  - Production discovery sockets (the multicast socket and all four strategies' outbound probes)
    never received the live `Network` binder at all — spec §11 layer 3 was unimplemented for the
    phase most likely to leak over cellular. Fixed by threading the binder through the actual
    production wiring path (`PeerWiring.kt`), not just an injectable test seam.
  - `onNetworkChanged` was unsynchronized; overlapping `ConnectivityManager` callbacks could race
    and an uncaught exception could crash the service on a routine WiFi transition. Fixed with a
    lock and a recovery `catch`.
  - The first fix round for the above introduced two new regressions, caught by re-review: the
    "network already applied" dedup was recorded even when the apply had failed, permanently
    blocking retry for that network; and `onLost` forwarded `ConnectivityManager.activeNetwork`
    unfiltered, which could be cellular, defeating the layer-3 guarantee the same fix round had
    just added elsewhere. Both fixed in a second round.
  - `BootReceiver` used a foreground-service type (`dataSync`) not eligible for a BOOT_COMPLETED-
    triggered start on Android 14+, which would throw uncaught. Fixed to use `connectedDevice`,
    confirmed correct on the real Android 15 device used for Task 13.

## Scope gaps carried forward, not closed in this branch

### Cross-implementation conformance (Task 13) only partially completed

See the dedicated sections above. The primary scenario's network topology was confirmed and its
one real blocking defect (AP-interface binding) was found and fixed on real hardware, but full
end-to-end control-channel handshake between the .NET and Android implementations remains blocked
on an unresolved TLS-interop gap. No matrix row beyond bind-address/reachability was completed.

### `resumeActivePulls`'s daemon threads are not cancelled on `close()`

Carried forward from Task 12's own report: in-flight resume threads are ad hoc daemon threads, not
coroutine-scoped, and `SlipstreamPeer.close()` does not cancel one already running. Low risk given
daemon threads die with the process, but not a clean shutdown.

### `android/app` has thin test coverage beyond what the Task 12 fix rounds added

The foreground service, boot receiver, and main activity are framework glue with limited unit
coverage relative to `:core`. Reasonable in general, but Task 12's own review noted this is exactly
the class of code where a missed BOOT_COMPLETED-eligibility bug slipped through the first pass —
`BootReceiverTest` now covers that specific regression, but broader `:app`-level coverage remains
thinner than `:core`'s.
