# Core fixes — direct work

Not a full plan. Four independent fixes to merged code, each small enough to do directly.
They share no files, so they can be worked in parallel.

**Baseline:** `main` at the Android-core merge. `dotnet test windows/Slipstream.sln` reports
**303/304 with one flaky failure** (fix 2 below). Android: `./gradlew :core:testDebugUnitTest`.

**Rules that still apply:** never disable, skip, or weaken a test to get green. Never weaken a
security property. If a fix cannot be made to work, stop and report rather than papering over it.

---

## Fix 1 — .NET ↔ Android TLS handshake (Critical, blocks everything)

**File:** `windows/src/Slipstream.Core/Control/PinnedTls.cs`

**Symptom** (from `2026-08-25-android-core-deviations.md`): `PinnedTls.AuthenticateAsClientAsync`
never completes against Android's `ControlServer`, while `openssl s_client` with a client cert
against the same server succeeds and returns a valid certificate. Forcing TLS 1.2 did not help.
Because of this, **no cross-implementation transfer has ever completed** and no wireless throughput
number exists.

**Leading hypothesis.** Line 26 sets:

```csharp
ClientCertificates = [identity.Certificate],
```

.NET filters that collection against the `certificate_authorities` list in the server's
`CertificateRequest`. Android's JSSE sends a non-empty CA list; a self-signed certificate's issuer
is not in it; .NET therefore selects **no** client certificate. Android's server requires one
(`setNeedClientAuth(true)`), so the handshake stalls. `openssl s_client -cert` sends the
certificate unconditionally — which is exactly why it works, and why the TLS-version experiment
changed nothing: the behaviour is version-independent.

**Change**

```csharp
await stream.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
{
    TargetHost = "slipstream",
    ClientCertificates = [identity.Certificate],

    // Android's JSSE sends a certificate_authorities list that cannot contain a
    // self-signed issuer, so .NET's default selection logic sends NO client
    // certificate and the peer — which requires one — stalls the handshake.
    // Selecting unconditionally bypasses that filter. Safe here because there is
    // exactly one certificate and exactly one peer; the trust decision is the
    // fingerprint pin below, never the issuer list.
    LocalCertificateSelectionCallback = (_, _, _, _, _) => identity.Certificate,

    EnabledSslProtocols = SslProtocols.Tls13 | SslProtocols.Tls12,
    CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
    RemoteCertificateValidationCallback = (_, certificate, _, _) =>
        certificate is not null && acceptFingerprint(Fingerprint.Of(certificate.GetRawCertData())),
}, cancellationToken);
```

**Verification available without a phone**

1. `dotnet test windows/Slipstream.sln` — the existing .NET↔.NET handshake tests must still pass.
   That proves no regression; it does **not** prove the interop fix.
2. Add a test asserting the callback is consulted and returns the identity certificate even when
   the acceptable-issuers array is empty:

```csharp
[Fact]
public async Task Client_sends_its_certificate_even_when_the_server_advertises_no_acceptable_issuers()
{
    // Android's server advertises issuers .NET can never match. If selection is left
    // to the default filter, no certificate is sent and the peer stalls.
    var serverIdentity = DeviceIdentity.CreateNew("Server");
    var clientIdentity = DeviceIdentity.CreateNew("Client");

    var (listener, seenClientCert) = StartServerCapturingClientCertificate(serverIdentity);
    try
    {
        using var tcp = new TcpClient();
        await tcp.ConnectAsync((IPEndPoint)listener.LocalEndpoint, _cts.Token);

        await using var stream = await PinnedTls.AuthenticateAsClientAsync(
            tcp.GetStream(), clientIdentity, _ => true, _cts.Token);

        Assert.Equal(clientIdentity.Fingerprint,
            Fingerprint.Of((await seenClientCert.Task).GetRawCertData()));
    }
    finally { listener.Stop(); }
}
```

**If the hypothesis is wrong.** Do not keep guessing. Stop, report, and record what was ruled out —
the next step is a packet capture of the handshake (Wireshark on the PC, filtered to port 53321),
looking specifically at whether the client's `Certificate` message is empty.

**Status when done:** report whether this is *fixed* (proven against a phone) or *plausibly fixed*
(unit-verified only). Do not claim the former without hardware.

---

## Fix 2 — flaky `MediaServerTests` — **HYPOTHESIS DISPROVEN, superseded by Fix 5**

> **Correction, after investigation.** The WPAD/proxy hypothesis below is **wrong** and the change
> was reverted, not committed. It was tested properly and failed the bar: 6 green out of 10 runs,
> with and without `UseProxy = false` — identical failure rate and identical 18 s duration either way.
>
> The real cause is a **product bug in `MediaServer`**, not a test artifact. The failure is a
> server-side TCP reset *while the client is reading the body*
> (`SocketException: An existing connection was forcibly closed`, thrown from
> `HttpContent.LoadIntoBufferAsyncCore` — headers arrive fine). Only tests that read the full
> 100 KB body fail; the 404, 416, and small-range tests never do.
>
> **The claim below that "the blast radius is tests only" and there is no §11 concern was also
> wrong.** `MediaServer` is product code and the RST is emitted by the server, so a real media
> player streaming a file is subject to the same truncation.
>
> Superseded by **Fix 5**. The section is kept rather than deleted so the ruled-out hypothesis and
> its evidence stay on the record.

**Files:** `windows/tests/Slipstream.Core.Tests/Media/MediaServerTests.cs`,
`windows/tests/Slipstream.Core.Tests/Control/SlipstreamSessionTests.cs`

**Symptom.** `Reports_the_content_type_from_the_extension` fails intermittently, taking **19 s**
against ~190 ms when passing. Verified across repeated runs: fails on a cold process, passes on
warm ones. Which test fails varies, because the cost lands on whichever runs first.

**Cause.** Both files construct a bare `new HttpClient()`, which on Windows performs system proxy
auto-detection (WPAD) on first use in a process. That stalls 15–20 s — matching the observed
duration and the first-test-only pattern.

Worth noting what this is *not*: `HttpClient` appears in zero product files, so the shipped app does
not do proxy discovery and there is no spec §11 violation. The blast radius is tests only.

**Change** — in both files:

```csharp
// A bare HttpClient performs WPAD proxy discovery on first use, stalling 15-20s and
// making whichever test runs first flaky. Slipstream only ever talks to a LAN peer.
private readonly HttpClient _http = new(new HttpClientHandler { UseProxy = false })
{
    Timeout = TimeSpan.FromSeconds(10),
};
```

The explicit `Timeout` matters independently: without it a genuine server hang would surface as a
100-second test rather than a fast, legible failure.

**Verification.** Run the full suite **10 times** and confirm 304/304 every time:

```bash
for i in $(seq 1 10); do dotnet test windows/Slipstream.sln --nologo -v q 2>&1 | grep -E "Passed!|Failed!"; done
```

Ten consecutive greens is the bar. One failure means the hypothesis is wrong and the real cause is
a race in `MediaServer`'s accept path — report that rather than raising the timeout.

---

## Fix 3 — `onNetworkChanged` readiness gate (Android, real bug)

**File:** `android/core/src/main/kotlin/com/slipstream/core/SlipstreamPeer.kt`

**Symptom** (recorded as an open follow-up in `2026-08-25-android-core-deviations.md`): the
readiness gate was weakened while fixing the hotspot bind bug. `current()` can now return non-null
from an unrelated interface while the newly-reported network's own interface is still coming up, so
servers can bind a stale or wrong address **without the dedup logic triggering a corrective retry**.

That is the same class as the Critical bug it was introduced alongside, and it can silently
reproduce that bug's symptom: servers listening on an interface no peer can reach.

**Change.** Make the gate track the *specific* network being reported rather than "any local
address exists". Concretely: `onNetworkChanged(network)` must not treat the peer as ready until
`current()` returns an address that actually belongs to an interface associated with `network` (or,
when `network` is null, until enumeration is stable across two reads a short interval apart). If
readiness is not reached within a bounded window, retry rather than binding optimistically.

**Tests required**

- A network change reporting an interface that is not yet up does **not** start servers on a
  stale address; once it comes up, servers bind the correct one.
- Two rapid changes to the same network still dedup (no bind storm).
- A change to a genuinely different network always rebinds.

**Do not** close this by widening the dedup window — that hides the race rather than fixing it.

---

## Fix 4 — STA+AP tie-break (Android)

**File:** `android/core/src/main/kotlin/com/slipstream/core/net/AndroidNetworkInfo.kt`

**Symptom** (open follow-up): when the device is simultaneously a WiFi client and hosting a
hotspot, both interfaces tie at the top of the `wlan/ap` priority band and the winner is
enumeration order — stable within a boot, but unproven which one wins. If the wrong one wins it
reproduces the original bind bug for that configuration.

**Change.** Make the tie deterministic and correct-by-intent: when two interfaces tie, prefer the
one whose address range contains a default gateway the device is *serving* (the AP side) over one
where the device is a client. Failing that, break the tie on a stable, documented key (lowest
interface index) so behaviour is at least reproducible and testable.

**Test required.** A unit test with two synthetic interfaces both matching the top priority band,
asserting the documented winner. Note in the code comment that this path is unit-tested but **not**
hardware-verified in an actual STA+AP configuration, so the deviations record stays honest.

---

---

# Round 2

Fixes 1, 3, and 4 are merged. Fix 2's hypothesis was disproven and is superseded below.

---

## Fix 5 — `MediaServer` truncates responses on teardown (product bug)

**File:** `windows/src/Slipstream.Core/Media/MediaServer.cs`

**Symptom.** Intermittently — roughly 4 runs in 10 — a client reading a media response gets the
headers, then a TCP reset partway through the body. Surfaces today as flaky
`MediaServerTests`, but it is a real streaming defect: a media player would see a truncated file.

**Cause.** `HandleAsync`'s `finally` calls `client.Client.DisconnectAsync(reuseSocket: false)` and
then `client.Dispose()` immediately after `ServeFileAsync` returns. Writing to a socket only hands
bytes to the OS send buffer; aborting the socket before the stack has drained it discards whatever
is still queued. The file's own comments describe a previously-fixed "forcibly closed" bug with the
same error string, which suggests that earlier fix reduced the frequency rather than removing the
cause.

> **Correction, after implementation. The prescribed change below is WRONG — do not follow it.**
>
> `Shutdown(SocketShutdown.Send)` reproduces the bug at the original rate. It was measured with a
> standalone out-of-process harness, 40 transfers per strategy:
>
> | server teardown after the last write | failures / 40 |
> |---|---|
> | `Shutdown(Send)` immediately | 7, 5, 2 |
> | `Shutdown(Send)` + drain — *the recipe below* | 8–11 |
> | drain to EOF first, then `Shutdown` | 8 |
> | **drain only, never touch the send side** | **0, 0, 0** |
>
> The trigger is *any* server-initiated close while the tail is still in flight; `Shutdown(Send)`
> is not meaningfully different from the old `DisconnectAsync`. There is also a second trap:
> `Shutdown` flips `TcpClient.Connected` to false, so `GetStream()` inside the drain throws
> `InvalidOperationException` and the drain silently never runs — read via `Socket.ReceiveAsync`.
>
> **What actually shipped (commit `f2788fb`): the server never initiates the close.** Every response
> carries `Content-Length` and `Connection: close`, so the client knows where the body ends without
> a FIN and hangs up first; the server reads until that close under a 3 s bound, then disposes. The
> drain also empties the receive buffer, which independently stops a close becoming an RST.
> Verified: ten consecutive green runs, and Core suite duration dropped from ~19 s to ~7 s.

**Change (superseded — see the correction above).** Close gracefully instead of aborting:

1. `await stream.FlushAsync(...)` (already done in `ServeFileAsync`).
2. `client.Client.Shutdown(SocketShutdown.Send)` — sends FIN, letting queued data drain.
3. Read from the socket until EOF, or until a short bounded timeout, so the peer's ACK/close is
   observed before the handle goes away.
4. Only then `Dispose()`.

Wrap the shutdown in its own try/catch: a client that has already vanished must not surface an
exception on a response that was otherwise served correctly.

**Verification bar — non-negotiable.** Ten consecutive full-suite runs, all green:

```bash
for i in $(seq 1 10); do dotnet test windows/tests/Slipstream.Core.Tests/Slipstream.Core.Tests.csproj --nologo -v q 2>&1 | grep -E "Passed!|Failed!"; done
```

A single green run proves nothing here — the bug reproduces about 40% of the time. If ten runs do
not come back clean, report the remaining failure mode rather than adding a retry or a timeout to
paper over it.

**Also check:** `PeerHostTests.Reports_Lost_then_recovers_on_reconnect` (in `Slipstream.App.Tests`)
fails with the same ~18 s signature. Determine whether it shares this cause. If it does, this fix
closes it; if not, say so — do not assume.

---

## Fix 6 — the solution test run silently skips `Slipstream.App.Tests`

**Files:** `windows/Slipstream.sln`, `windows/tests/Slipstream.App.Tests/Slipstream.App.Tests.csproj`,
`.github/workflows/windows-core.yml`

**Symptom.** `dotnet test windows/Slipstream.sln` reports *"A total of 1 test files matched the
specified pattern"* and runs only `Slipstream.Core.Tests`. Run directly, `Slipstream.App.Tests` has
**117 tests, 3 failing**.

The project is referenced by the solution, so this is not a missing entry — it is a targeting or
discovery problem (the App test project targets `net9.0-windows10.0.19041.0` with a platform/RID
that the solution-level run does not resolve).

**Why this matters more than three failures.** CI runs the same solution command. Every Windows
app test has therefore been invisible since Plan 5 merged, and the branch merged green with three
red tests. This is the same failure shape as the commented-out thumbnail tests: a green badge over
tests that never ran. Fix the visibility first — the three failures are the smaller half.

**Change**

1. Make `dotnet test windows/Slipstream.sln` execute **both** test projects. Confirm by the
   *"test files matched"* line reporting 2, not by a passing exit code.
2. Fix the two `AutostartServiceTests` failures. They fail in under half a second, which points at
   an environment precondition — Task Scheduler registration typically needs elevation. If the test
   genuinely cannot run unelevated, it must **fail loudly with a clear reason or be restructured to
   test the logic without touching the real scheduler** — it must not be silently skipped.
3. `PeerHostTests.Reports_Lost_then_recovers_on_reconnect` — coordinate with Fix 5; it may already
   be fixed there.

**Verification.** `dotnet test windows/Slipstream.sln` reports 2 test files and zero failures, ten
times consecutively.

---

---

# Round 3 — closed

> **Outcome.** Fix 7 was **not a test bug**. `ControlServer` registered a connection in
> `Connections` only *after* its side of the TLS handshake finished, while the peer had already
> completed its own half and begun using the link — so `Connections` was never a picture of the
> live sockets, and anything iterating it to sever or shut down connections silently skipped one
> the peer was actively talking on. A second defect surfaced alongside: `DisposeAsync()` stopped
> the listener but never closed accepted sockets, so live TLS sessions outlived the server object.
>
> **Product impact:** a consumer enumerating `Connections` to shut down or account for live links
> misses in-handshake connections and leaks them past shutdown — a real peer can be left with a
> live control channel the local side believes it closed.
>
> **Fix (`b5ed2e6`):** track every accepted `TcpClient` synchronously in the accept loop, before
> any handshake, and add `CloseActiveConnections()` (mirroring `BulkServer.BreakActiveConnections()`)
> which resets all of them including in-handshake ones. `DisposeAsync` calls it. `Connections`
> remains an observation API, now documented as deliberately incomplete.
>
> The two `serverUsesFixedPorts: true` tests move ~80 MB and load the thread pool, delaying the
> server's handshake continuation past the break — which is what turned an always-present race
> into a deterministic 5/5 failure. Nothing static leaked; ports were not contended; xUnit
> parallelism was not involved. All three of this document's original hypotheses were wrong.
>
> **Subnet sweep (`7f7cb22`):** replaced the wall-clock assertion with a direct measurement —
> `FakeProbe` records peak simultaneous in-flight probes via `Interlocked`, asserting `>= 50` of
> 253. A serial sweep can never exceed 1 at any load, so this tests the property the test cares
> about rather than how busy the machine is.
>
> **Verification.** The implementing agent measured ten consecutive full-solution runs, all green.
> Independently re-verified after merge: 6/6 Core runs green at 7 s each, and 4/5 full-solution
> runs green (306 Core + 120 App).
>
> **One caveat, recorded rather than smoothed over.** During that 5-run pass, one Core run failed
> while the machine was under heavy concurrent load from an unrelated Gradle build — the same pass
> saw a 29 s Core run against a normal 7 s. The failing test name was not captured, and it did not
> reproduce across six subsequent runs. It is most likely another load-sensitive timing assertion
> of the kind the subnet-sweep fix addressed, but that is a guess, not a finding. **If a Core test
> fails under load again, capture the name** — there may be one more elapsed-time assertion worth
> converting to a direct measurement.

## Fix 7 — `PeerHostTests` fail only inside the suite — **root cause was a product bug**

**File:** `windows/tests/Slipstream.App.Tests/PeerHostTests.cs` (and whatever it shares state with)

**Status after round 2:** `dotnet test windows/Slipstream.sln` is now **306/306 Core + 119/120 App**,
stable across five consecutive runs. The single remaining failure is deterministic, not flaky.

**Symptom.** `Reports_Lost_then_recovers_on_reconnect` fails 5 runs out of 5 with
`TimeoutException: Condition was not met in time` — `host.State` never reaches `Lost` within 10 s
of `BreakControlConnectionAsync()`. It **passes in about 2 s when run in isolation.**
`A_network_change_tears_down_rediscovers_and_resumes` in the same file failed once under load.

**Ruled out already — do not re-derive.** It is not the `MediaServer` teardown bug (Fix 5): no HTTP
and no `MediaServer` is involved, and it survived that fix unchanged. It is not a timing flake —
5/5 in-suite versus passing in isolation is interference, not variance.

**Where to look.** Something shared across `Slipstream.App.Tests` is keeping the control channel's
liveness detection from firing: a static or leaked `SlipstreamPeer`/`PeerHost` still holding the
port, an `xUnit` collection running these in parallel with tests that bind the same fixed ports, or
a `NetworkChange` handler surviving a prior test. Note that this project has already shipped one
static-event-handler leak (`SlipstreamPeer`, closed in Plan 2b) — the same shape is worth checking
first.

**Bar.** Ten consecutive `dotnet test windows/Slipstream.sln` runs with **zero** failures across
both projects. Do not fix it by putting the test in its own collection unless that is genuinely the
right answer *and* the underlying shared state is documented — isolating a test to hide
interference leaves the interference in the product.

**Also unowned:** `SubnetSweepStrategyTests.Runs_probes_concurrently_rather_than_serially` failed
once under load in round 2. It is a wall-clock assertion (serial would be 12.7 s; it allows 2 s),
so it is inherently load-sensitive. Decide whether to widen the bound or measure concurrency
directly rather than by elapsed time.

---

---

# Round 4 — closed

> **Outcome.** Pairing could not work on the product's primary topology, and the cause was a
> **design error in Plan 1b Task 5, not an implementation slip.** `PairingDiscovery` was
> specified and built to subscribe to `MulticastStrategy` and nothing else. Paired discovery
> already races four strategies *precisely because multicast is unreliable*; pairing discovery
> was handed the single strategy that fails on an Android softAP. The code did what the plan
> said. The plan was wrong.
>
> Spec §1 names the phone hotspot as the **primary** scenario: the PC is a DHCP client of the
> phone's softAP and the phone is the PC's default gateway. Measured on real hardware before
> the fix — PC's default route `NextHop 10.199.176.137` on Wi-Fi; PC joined `224.0.0.167:53320`
> and received **nothing in 8 seconds** with the phone app running; `Test-NetConnection
> 10.199.176.137:53321` succeeded and a raw .NET TLS handshake against it completed in
> **21–216 ms**. The peer was trivially reachable by gateway probe and completely invisible to
> multicast.

## Fix 8 — pairing discovery is multicast-only (design error, both platforms)

**Files:** `windows/src/Slipstream.Core/Pairing/PairingDiscovery.cs`,
`windows/src/Slipstream.Core/Control/ControlClient.cs`, `windows/src/Slipstream.Core/SlipstreamPeer.cs`,
`android/core/.../pairing/PairingDiscovery.kt`, `android/core/.../pairing/TlsPairingProbe.kt`

**Change.** `PairingDiscovery` now races the same ladder as `DiscoveryCoordinator` — multicast,
gateway probe, subnet sweep — on **both** platforms. Multicast is kept; it is correct on a normal
router. The gateway probe is the decisive arm in hotspot mode. The sweep is the backstop, bounded
to a /24 by the existing `SubnetMath`, which refuses anything wider rather than attempting it.

The **only** difference from paired discovery is the trust filter, and it is deliberate: paired
discovery requires a fingerprint match; pairing discovery accepts any peer, because the six-digit
code compared by two humans is what establishes trust (`protocol/pairing.md`). Discovery therefore
produces a *candidate* — an address plus a TLS-verified fingerprint — and nothing more.

**What was explicitly not weakened.**

- Everything stays gated on the open 120-second `PairingWindow`: checked before the first probe,
  again per host mid-sweep, and again before any result is returned, with a watcher arm that ends
  the race the moment the gate shuts. Outside the window nothing probes, nothing listens, and
  unpaired inbound connections are still dropped before a message is read.
- Probes connect **unpinned** (`ControlClient.ConnectForPairingAsync` / `TlsPairingProbe`) — there
  is nothing to pin against yet. `LanGuard` applies to every probe.
- Mutual six-digit confirmation is untouched. Discovery never pairs and never persists;
  `PairingCoordinator` still owns that alone.
- `MulticastStrategy` still has exactly **one** `ReceiveAsync` on its socket. The Windows ladder
  subscribes to the existing fan-out — the two-concurrent-receives bug that was the Windows core's
  one shipped Critical is not reintroduced.

Both platforms were changed together. A ladder on one side only means pairing works in one
direction only.

## Fix 9 — a discovery probe collapsed the responder's pairing window (Android, found on hardware)

**File:** `android/core/src/main/kotlin/com/slipstream/core/SlipstreamPeer.kt`

Found only by driving the fixed ladder against a real phone: the PC found the phone in **419 ms**
by gateway probe, and then pairing failed, with the phone showing *"Pairing declined."* within a
second.

The gateway probe finds a peer by completing an unpinned TLS handshake and hanging up — that *is*
what "found" means when multicast is dead. On the responder, that hang-up ran `PairingCoordinator`
to a `false` result which was posted straight to the pending outcome queue, so `awaitPairing`
returned and closed the window **before the initiator's real connection arrived**. The probe
cancelled the pairing it existed to enable.

An exchange that never put a code in front of the user is not an answer. The outcome is now posted
only on a successful pairing, or when the user was actually asked — tracked by wrapping the confirm
callback, so the flag cannot drift from whether the prompt really fired.

This **tightens** the gate. The window still closes on a real outcome, on user cancel, and on its
own 120-second expiry — and an unpaired stranger who connects and drops can no longer cancel the
user's attempt, which was a denial of service on pairing in its own right, reachable by anyone able
to open a socket during the window.

## Verification

| Suite | Result |
| --- | --- |
| `dotnet test windows/Slipstream.sln` | **312 Core** (306 + 6 new) + **122 App**, 0 failures |
| `./gradlew :core:testDebugUnitTest` | **255**, 0 failures (249 + 6 new) |
| `./gradlew :app:testDebugUnitTest` | **133**, 0 failures |
| `bash android/scripts/check-meridian-tokens.sh` | passes |

**Tests added** (6 Windows, 6 Android core — the regression test was written first and confirmed
red against the pre-fix code on both platforms):

- gateway probe finds an unpaired peer when multicast yields nothing (**the hotspot case**)
- subnet sweep finds one when there is no gateway, bounded to the /24, never probing ourselves
- multicast still wins when it works, with the full ladder running
- nothing probes at all while the window is closed — asserted on a recording probe, not inferred
- the search stops the moment the window closes mid-search
- a peer found by any strategy is a candidate only; nothing is persisted without mutual confirmation
- (Android) a discovery probe must not collapse the responder's pairing window — Fix 9's regression
  test, confirmed red before the fix

## Hardware result — **discovery verified, full pairing NOT verified**

Stated plainly, because the distinction matters.

**Verified on the live hotspot** (PC `10.199.176.38/24`, gateway/phone `10.199.176.137`, phone
running the rebuilt app), driving the real ladder and the real unpinned probe:

```
window closed : found=null in 2 ms          <- nothing touched the network
window open   : found=10.199.176.137:53321
                fp=a9b8061f...aaafb294 in 197 ms
connected to  : 10.199.176.137:53321 fp=a9b8061f...aaafb294
```

Repeated at 419 ms and 197 ms across two runs. Multicast contributed nothing on either. For
contrast, the unmodified `pair-mode` harness on the same machine found **no peer at all** in the
full 120 seconds.

**Not verified: the six-digit code exchange.** I did not observe matching codes on both devices, so
the hotspot row must **not** be marked verified. Two things got in the way, both worth recording:

1. **A separate, pre-existing defect blocks the fix in production on this PC.**
   `NetworkInfo.Current()` returns the **first** up non-loopback NIC with a LAN-local IPv4. On this
   machine that is `vEthernet (Default Switch)` — a Hyper-V virtual switch at `192.168.112.1/20`
   with **no gateway** — not Wi-Fi. So the gateway arm gets `gateway = null` and does nothing, and
   the sweep gets `/20`, which `SubnetMath` refuses as wider than a /24. The ladder is starved of
   the correct network before it starts. Verification above was obtained by supplying the real
   Wi-Fi `LocalNetwork` directly; nothing else was stubbed. **This is unowned and should be the
   next fix** — it defeats paired discovery on this machine too, and it is not something Fix 8 can
   route around. `SlipstreamPeer` hard-constructs `new NetworkInfo()`, so it is not injectable
   either.
2. The phone went into an active personal call mid-test. Hardware driving was stopped there rather
   than continuing to send taps to a device in use.

**Confidence:** the discovery half is **verified** — measured end to end against real hardware on
the real topology, including the window-closed case. The complete pairing handshake over the
hotspot is **plausible**: every component is exercised green by tests, the unpinned TLS connection
to the phone was established and its certificate fingerprint verified, and Fix 9 removed the one
observed reason the exchange died — but no human compared two codes.

---

## Reporting

Each fix reports separately: what changed, whether tests pass, and — for fixes 1, 3, and 4 —
whether the result is *verified* or only *plausible*, since none can be fully proven without
hardware. Say which plainly.

For fixes 5 and 6, state the **ten-run** result explicitly. A single green run is not a result.
