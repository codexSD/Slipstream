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

# Round 3 — open

## Fix 7 — `PeerHostTests` fail only inside the suite (cross-test interference)

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

## Reporting

Each fix reports separately: what changed, whether tests pass, and — for fixes 1, 3, and 4 —
whether the result is *verified* or only *plausible*, since none can be fully proven without
hardware. Say which plainly.

For fixes 5 and 6, state the **ten-run** result explicitly. A single green run is not a result.
