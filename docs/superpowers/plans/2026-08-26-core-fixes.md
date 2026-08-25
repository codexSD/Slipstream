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

## Fix 2 — flaky `MediaServerTests` (main is red)

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

## Reporting

Each fix reports separately: what changed, whether tests pass, and — for fixes 1, 3, and 4 —
whether the result is *verified* or only *plausible*, since none can be fully proven without
hardware. Say which plainly.
