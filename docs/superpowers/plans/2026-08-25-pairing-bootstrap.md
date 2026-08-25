# Pairing Bootstrap — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let two never-paired Slipstream devices find each other, display a matching six-digit code, and reach a mutual trusted state — closing the gap recorded in Plan 1's deviations, where `PairOfferPayload` is declared but nothing constructs it and both sides refuse unpaired connections outright.

**Architecture:** Pairing is gated behind an explicit, time-boxed **pairing window** the user opens on both devices. Outside that window the accept path behaves exactly as it does today — unpaired connections are dropped before a single message is read. Inside it, an unpaired TLS connection is accepted into a *restricted* handler that speaks only `pair.*` messages. Both sides derive the six-digit code independently from the two certificate fingerprints, so no secret crosses the wire; each device persists the peer only after its own user confirms **and** the remote confirms.

**Tech Stack:** .NET 9, C# 13, xUnit. No new dependencies.

**Spec:** [`docs/superpowers/specs/2026-08-25-slipstream-design.md`](../specs/2026-08-25-slipstream-design.md) — §4 (identity and pairing), §5 (discovery), §11 (LAN-only).

**Upstream plan:** [`2026-08-25-core-discovery-control.md`](2026-08-25-core-discovery-control.md) (Plan 1, merged) and its [deviations record](2026-08-25-core-discovery-control-deviations.md).

---

## Why this is its own plan

Plan 1 delivered the correct default: unknown devices get nothing, with no prompt and no override path. This plan deliberately opens a narrow hole in that default, which makes it the most security-sensitive work remaining in Core. It gets its own branch and its own review rather than riding along as trailing tasks on a feature plan.

It has **no dependency on Plan 2** and touches disjoint files, so the two run in parallel.

---

## Preconditions — verified present on `main`

These were confirmed against the merged tree before this plan was written.

```csharp
// Slipstream.Core.Identity
sealed class DeviceIdentity { string DeviceId; string DisplayName; X509Certificate2 Certificate; string Fingerprint; }
sealed record PairedPeer(string DeviceId, string Fingerprint, string DisplayName, DateTimeOffset PairedAt);
sealed class PairedPeerStore { PairedPeer? Current; bool IsPaired; void Pair(PairedPeer); void Unpair(); bool Trusts(string); }
static class PairingCode { static string Derive(string fingerprintA, string fingerprintB); }   // already tested + vectored
static class Fingerprint { static string Of(X509Certificate2); static string Of(ReadOnlySpan<byte>); }

// Slipstream.Core.Control
sealed record PairOfferPayload(int Version, string DeviceId, string Name, string Fingerprint);  // declared, unused
sealed class ControlServer : IAsyncDisposable { IPEndPoint ListenEndPoint; event Func<ControlConnection, CancellationToken, Task>? PeerConnected; Task RunAsync(CancellationToken); }
sealed class ControlClient { PeerProbe CreateProbe(TimeSpan); Task<ControlConnection?> ConnectAsync(IPEndPoint, TimeSpan, CancellationToken); }
sealed class ControlConnection : IAsyncDisposable { string PeerFingerprint; IPEndPoint RemoteEndPoint; Task SendAsync(...); Task<ControlMessage?> ReceiveAsync(...); }
static class PinnedTls { Task<SslStream> AuthenticateAsClientAsync(Stream, DeviceIdentity, Func<string,bool>, CancellationToken); Task<SslStream> AuthenticateAsServerAsync(Stream, DeviceIdentity, CancellationToken); string FingerprintOf(SslStream); }

// Slipstream.Core.Discovery
sealed record PeerAnnouncement(int Version, string DeviceId, string DisplayName, string Fingerprint, int ControlPort, AnnouncementKind Kind);
enum AnnouncementKind { Announce, Query }
sealed class MulticastStrategy : IDiscoveryStrategy, IAsyncDisposable { IPEndPoint ListenEndPoint; Task RespondToQueriesAsync(CancellationToken); }

// Slipstream.Core.Net
static class LanGuard { static bool IsLocal(IPAddress); static void EnsureLocal(IPAddress); }
sealed record LocalNetwork(IPAddress LocalAddress, IPAddress? Gateway, int PrefixLength, string Key);
```

**Note on `MulticastStrategy`:** Plan 1's final review replaced the two-independent-receive-loops design with a single reader loop fanning datagrams out to the responder and to per-call `Channel<>` subscribers. Task 5 extends that fan-out rather than adding a second reader — **do not open another `ReceiveAsync` on that socket.** That race is the one Critical bug Plan 1 shipped and fixed; re-introducing it would be worse than the original.

## Global Constraints

- **Outside an open pairing window, behaviour is byte-for-byte what it is today.** Unpaired inbound connections are dropped before any message is read. Plan 1's test `Server_drops_a_connection_from_an_untrusted_fingerprint` must keep passing, unmodified.
- **The pairing window is explicit and time-boxed:** opened only by a direct user action, **120 seconds**, closing automatically on expiry or on a successful pairing.
- **Restricted handler.** A connection accepted through the pairing path may exchange only `pair.offer`, `pair.offer.ok`, `pair.confirm`, and `pair.cancel`. Every other type is ignored — it must never reach the browse/transfer session.
- **Exactly one paired peer at a time.** A successful pairing replaces any existing peer (§4).
- **The code is derived, never transmitted.** Both sides compute `PairingCode.Derive(localFingerprint, remoteFingerprint)` after the TLS handshake. No pairing secret appears on the wire.
- **Mutual confirmation required.** A device persists the peer only when its own user has confirmed **and** a remote `pair.confirm` has arrived. A single-sided confirm never pairs.
- **TLS still applies** on the pairing path — the certificate is simply not pinned, since there is nothing to pin against yet. Plaintext pairing is not permitted.
- **LAN-only:** `LanGuard` applies to the pairing path exactly as to every other socket.
- **No new runtime dependencies.**
- **User-facing strings:** English, sentence case, direct, no apology.

---

## Threat model, stated explicitly

Write this into `protocol/pairing.md` in Task 7; it is recorded here so the implementer understands what the design is and is not defending against.

The six-digit code is `SHA-256(sorted(fpA) || sorted(fpB))` truncated to six decimal digits. It **binds both certificates**. An attacker interposing on the LAN presents their own certificate, which changes the derived code on at least one side — so the two displayed codes disagree and the user declines.

To defeat this the attacker must find a certificate whose fingerprint yields a six-digit collision against the victim pair: one chance in a million per attempt, with attempts bounded by a 120-second window that a human opened deliberately on both devices and is actively watching. For a personal two-device LAN tool that is proportionate.

What this does **not** defend against: a user who confirms without comparing the codes. The UI must show both codes prominently and say what the user is being asked to check. That is a UI obligation, recorded here and carried into the UI plans.

---

## File Structure

```
windows/src/Slipstream.Core/
  Pairing/PairingWindow.cs          # the time-boxed gate
  Pairing/PairingSession.cs         # code derivation + mutual-confirm state machine
  Pairing/PairingDiscovery.cs       # find an unpaired peer, window-gated
  Pairing/PairingCoordinator.cs     # orchestration, both directions
  Control/ControlServer.cs          # MODIFIED: window-gated unpaired accept path
  Control/ControlClient.cs          # MODIFIED: ConnectForPairingAsync
protocol/
  pairing.md                        # normative flow + threat model
windows/tests/Slipstream.Core.Tests/Pairing/…
windows/tools/Slipstream.Harness/Program.cs   # MODIFIED: pair-mode command
```

---

## Task 1: The pairing window

**Files:**
- Create: `windows/src/Slipstream.Core/Pairing/PairingWindow.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Pairing/PairingWindowTests.cs`

**Interfaces:**
- Consumes: nothing.
- Produces: `sealed class PairingWindow { PairingWindow(TimeProvider? time = null); static readonly TimeSpan Duration = TimeSpan.FromSeconds(120); bool IsOpen { get; } DateTimeOffset? ClosesAt { get; } void Open(); void Close(); event Action? Closed; }`
- `IsOpen` is false by default and becomes false again on expiry without anyone calling `Close`.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Pairing/PairingWindowTests.cs`:

```csharp
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingWindowTests
{
    private sealed class FakeTime(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan by) => _now += by;
    }

    private static FakeTime At(string instant) => new(DateTimeOffset.Parse(instant));

    [Fact]
    public void Is_closed_by_default()
    {
        // The safe default is the whole point: pairing is never implicitly available.
        Assert.False(new PairingWindow().IsOpen);
    }

    [Fact]
    public void Opens_on_an_explicit_call()
    {
        var window = new PairingWindow();
        window.Open();

        Assert.True(window.IsOpen);
        Assert.NotNull(window.ClosesAt);
    }

    [Fact]
    public void Closes_automatically_after_120_seconds()
    {
        var time = At("2026-08-25T10:00:00Z");
        var window = new PairingWindow(time);
        window.Open();

        time.Advance(TimeSpan.FromSeconds(119));
        Assert.True(window.IsOpen);

        time.Advance(TimeSpan.FromSeconds(2));
        Assert.False(window.IsOpen);
    }

    [Fact]
    public void Duration_is_120_seconds()
    {
        Assert.Equal(TimeSpan.FromSeconds(120), PairingWindow.Duration);
    }

    [Fact]
    public void Close_shuts_it_immediately()
    {
        var window = new PairingWindow();
        window.Open();
        window.Close();

        Assert.False(window.IsOpen);
        Assert.Null(window.ClosesAt);
    }

    [Fact]
    public void Reopening_extends_the_deadline()
    {
        var time = At("2026-08-25T10:00:00Z");
        var window = new PairingWindow(time);

        window.Open();
        time.Advance(TimeSpan.FromSeconds(100));
        window.Open();
        time.Advance(TimeSpan.FromSeconds(100));

        Assert.True(window.IsOpen);
    }

    [Fact]
    public void Raises_Closed_when_closed_explicitly()
    {
        var window = new PairingWindow();
        var raised = 0;
        window.Closed += () => raised++;

        window.Open();
        window.Close();

        Assert.Equal(1, raised);
    }

    [Fact]
    public void Closing_an_already_closed_window_does_not_raise_again()
    {
        var window = new PairingWindow();
        var raised = 0;
        window.Closed += () => raised++;

        window.Close();
        window.Close();

        Assert.Equal(0, raised);
    }

    [Fact]
    public void ClosesAt_is_null_once_expired()
    {
        var time = At("2026-08-25T10:00:00Z");
        var window = new PairingWindow(time);
        window.Open();

        time.Advance(TimeSpan.FromSeconds(200));

        Assert.Null(window.ClosesAt);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PairingWindowTests`
Expected: FAIL — `PairingWindow` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Pairing/PairingWindow.cs`:

```csharp
namespace Slipstream.Core.Pairing;

/// <summary>
/// The gate that makes pairing safe: an explicit, user-opened, time-boxed window.
///
/// Outside this window the accept path behaves exactly as it did before pairing
/// existed — unpaired connections are dropped before a message is read. The window
/// is the *only* thing that changes that, and it closes by itself.
/// </summary>
public sealed class PairingWindow(TimeProvider? time = null)
{
    public static readonly TimeSpan Duration = TimeSpan.FromSeconds(120);

    private readonly TimeProvider _time = time ?? TimeProvider.System;
    private readonly Lock _gate = new();

    private DateTimeOffset? _closesAt;

    /// <summary>Raised when the window closes explicitly. Expiry is silent — poll <see cref="IsOpen"/>.</summary>
    public event Action? Closed;

    public bool IsOpen
    {
        get
        {
            lock (_gate)
            {
                return _closesAt is { } deadline && _time.GetUtcNow() < deadline;
            }
        }
    }

    public DateTimeOffset? ClosesAt
    {
        get
        {
            lock (_gate)
            {
                if (_closesAt is { } deadline && _time.GetUtcNow() < deadline) return deadline;
                return null;
            }
        }
    }

    public void Open()
    {
        lock (_gate)
        {
            _closesAt = _time.GetUtcNow() + Duration;
        }
    }

    public void Close()
    {
        bool wasOpen;
        lock (_gate)
        {
            wasOpen = _closesAt is { } deadline && _time.GetUtcNow() < deadline;
            _closesAt = null;
        }

        if (wasOpen) Closed?.Invoke();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PairingWindowTests`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Pairing/PairingWindow.cs windows/tests/Slipstream.Core.Tests/Pairing/PairingWindowTests.cs
git commit -m "feat: add time-boxed pairing window"
```

---

## Task 2: The pairing session state machine

**Files:**
- Create: `windows/src/Slipstream.Core/Pairing/PairingSession.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Pairing/PairingSessionTests.cs`

**Interfaces:**
- Consumes: `PairingCode`, `PairedPeer`.
- Produces:
  - `enum PairingState { AwaitingOffer, AwaitingConfirmation, Paired, Cancelled }`
  - `sealed class PairingSession { PairingSession(DeviceIdentity localIdentity); PairingState State { get; } string? Code { get; } PairedPeer? Result { get; } void ReceiveOffer(PairOfferPayload offer, string verifiedFingerprint); void ConfirmLocally(); void ReceiveRemoteConfirm(); void Cancel(); }`
  - `Code` is non-null only once an offer has been received. `Result` is non-null only in `Paired`.
  - `verifiedFingerprint` is the fingerprint taken from the **TLS certificate**, not from the offer payload — an offer claiming a fingerprint it does not hold must not pair.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Pairing/PairingSessionTests.cs`:

```csharp
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingSessionTests
{
    private readonly DeviceIdentity _local = DeviceIdentity.CreateNew("Local PC");
    private readonly DeviceIdentity _remote = DeviceIdentity.CreateNew("Remote Phone");

    private PairOfferPayload RemoteOffer() => new(
        SlipstreamPorts.ProtocolVersion, _remote.DeviceId, _remote.DisplayName, _remote.Fingerprint);

    private PairingSession Started()
    {
        var session = new PairingSession(_local);
        session.ReceiveOffer(RemoteOffer(), _remote.Fingerprint);
        return session;
    }

    [Fact]
    public void Starts_awaiting_an_offer_with_no_code()
    {
        var session = new PairingSession(_local);

        Assert.Equal(PairingState.AwaitingOffer, session.State);
        Assert.Null(session.Code);
        Assert.Null(session.Result);
    }

    [Fact]
    public void Receiving_an_offer_derives_the_code()
    {
        var session = Started();

        Assert.Equal(PairingState.AwaitingConfirmation, session.State);
        Assert.Equal(PairingCode.Derive(_local.Fingerprint, _remote.Fingerprint), session.Code);
    }

    [Fact]
    public void Both_devices_derive_the_same_code()
    {
        // The order-independence that makes "compare these two numbers" work at all.
        var here = new PairingSession(_local);
        here.ReceiveOffer(RemoteOffer(), _remote.Fingerprint);

        var there = new PairingSession(_remote);
        there.ReceiveOffer(
            new PairOfferPayload(SlipstreamPorts.ProtocolVersion, _local.DeviceId, _local.DisplayName, _local.Fingerprint),
            _local.Fingerprint);

        Assert.Equal(here.Code, there.Code);
    }

    [Fact]
    public void An_offer_whose_claimed_fingerprint_differs_from_the_certificate_is_rejected()
    {
        // The payload is peer-supplied text; the certificate is proof. Only the
        // certificate may drive the code, or a MITM could forge a matching one.
        var session = new PairingSession(_local);
        var lying = new PairOfferPayload(
            SlipstreamPorts.ProtocolVersion, _remote.DeviceId, _remote.DisplayName, "a-fingerprint-it-does-not-hold");

        session.ReceiveOffer(lying, verifiedFingerprint: _remote.Fingerprint);

        Assert.Equal(PairingState.Cancelled, session.State);
        Assert.Null(session.Code);
    }

    [Fact]
    public void A_local_confirm_alone_does_not_pair()
    {
        var session = Started();
        session.ConfirmLocally();

        Assert.Equal(PairingState.AwaitingConfirmation, session.State);
        Assert.Null(session.Result);
    }

    [Fact]
    public void A_remote_confirm_alone_does_not_pair()
    {
        var session = Started();
        session.ReceiveRemoteConfirm();

        Assert.Equal(PairingState.AwaitingConfirmation, session.State);
        Assert.Null(session.Result);
    }

    [Fact]
    public void Both_confirmations_pair_regardless_of_order()
    {
        var localFirst = Started();
        localFirst.ConfirmLocally();
        localFirst.ReceiveRemoteConfirm();

        var remoteFirst = Started();
        remoteFirst.ReceiveRemoteConfirm();
        remoteFirst.ConfirmLocally();

        Assert.Equal(PairingState.Paired, localFirst.State);
        Assert.Equal(PairingState.Paired, remoteFirst.State);
    }

    [Fact]
    public void The_result_carries_the_certificate_fingerprint_and_the_offered_name()
    {
        var session = Started();
        session.ConfirmLocally();
        session.ReceiveRemoteConfirm();

        var result = session.Result!;
        Assert.Equal(_remote.DeviceId, result.DeviceId);
        Assert.Equal(_remote.Fingerprint, result.Fingerprint);
        Assert.Equal("Remote Phone", result.DisplayName);
    }

    [Fact]
    public void Cancelling_stops_any_later_confirmation_from_pairing()
    {
        var session = Started();
        session.Cancel();

        session.ConfirmLocally();
        session.ReceiveRemoteConfirm();

        Assert.Equal(PairingState.Cancelled, session.State);
        Assert.Null(session.Result);
    }

    [Fact]
    public void A_second_offer_is_ignored()
    {
        var session = Started();
        var code = session.Code;

        session.ReceiveOffer(RemoteOffer(), _remote.Fingerprint);

        Assert.Equal(code, session.Code);
        Assert.Equal(PairingState.AwaitingConfirmation, session.State);
    }

    [Fact]
    public void Confirming_before_an_offer_arrives_is_ignored()
    {
        var session = new PairingSession(_local);
        session.ConfirmLocally();
        session.ReceiveRemoteConfirm();

        Assert.Equal(PairingState.AwaitingOffer, session.State);
        Assert.Null(session.Result);
    }

    [Fact]
    public void An_offer_from_our_own_fingerprint_is_rejected()
    {
        // Self-discovery must never pair a device with itself.
        var session = new PairingSession(_local);
        session.ReceiveOffer(
            new PairOfferPayload(SlipstreamPorts.ProtocolVersion, _local.DeviceId, _local.DisplayName, _local.Fingerprint),
            _local.Fingerprint);

        Assert.Equal(PairingState.Cancelled, session.State);
    }
}
```

The lying-offer test is the security core of this task. The payload is attacker-controlled text; the certificate fingerprint is the only thing proven by the handshake, so only it may drive the code.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PairingSessionTests`
Expected: FAIL — `PairingSession` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Pairing/PairingSession.cs`:

```csharp
using Slipstream.Core.Control;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Pairing;

public enum PairingState
{
    AwaitingOffer,
    AwaitingConfirmation,
    Paired,
    Cancelled,
}

/// <summary>
/// One pairing attempt, on one device. Spec §4.
///
/// The six-digit code is derived from both certificate fingerprints and is never
/// transmitted — each side computes it and the user compares them by eye. Pairing
/// completes only on mutual confirmation: this device's user AND the remote.
/// </summary>
public sealed class PairingSession(DeviceIdentity localIdentity)
{
    private readonly Lock _gate = new();

    private PairOfferPayload? _offer;
    private string? _verifiedFingerprint;
    private bool _confirmedLocally;
    private bool _confirmedRemotely;

    public PairingState State { get; private set; } = PairingState.AwaitingOffer;

    /// <summary>The six digits to show the user. Null until an offer arrives.</summary>
    public string? Code { get; private set; }

    public PairedPeer? Result { get; private set; }

    /// <param name="verifiedFingerprint">
    /// Taken from the TLS certificate, never from <paramref name="offer"/>. The offer is
    /// peer-supplied text; the certificate is the only thing the handshake proves.
    /// </param>
    public void ReceiveOffer(PairOfferPayload offer, string verifiedFingerprint)
    {
        lock (_gate)
        {
            if (State != PairingState.AwaitingOffer) return;

            // A peer claiming a fingerprint it does not hold is either broken or hostile.
            if (!string.Equals(offer.Fingerprint, verifiedFingerprint, StringComparison.OrdinalIgnoreCase))
            {
                State = PairingState.Cancelled;
                return;
            }

            // Never pair with ourselves.
            if (string.Equals(verifiedFingerprint, localIdentity.Fingerprint, StringComparison.OrdinalIgnoreCase))
            {
                State = PairingState.Cancelled;
                return;
            }

            _offer = offer;
            _verifiedFingerprint = verifiedFingerprint;
            Code = PairingCode.Derive(localIdentity.Fingerprint, verifiedFingerprint);
            State = PairingState.AwaitingConfirmation;
        }
    }

    /// <summary>This device's user confirmed the codes match.</summary>
    public void ConfirmLocally()
    {
        lock (_gate)
        {
            if (State != PairingState.AwaitingConfirmation) return;

            _confirmedLocally = true;
            CompleteIfMutual();
        }
    }

    public void ReceiveRemoteConfirm()
    {
        lock (_gate)
        {
            if (State != PairingState.AwaitingConfirmation) return;

            _confirmedRemotely = true;
            CompleteIfMutual();
        }
    }

    public void Cancel()
    {
        lock (_gate)
        {
            if (State == PairingState.Paired) return;
            State = PairingState.Cancelled;
        }
    }

    private void CompleteIfMutual()
    {
        // A single-sided confirmation never pairs. Both users looked at the code.
        if (!_confirmedLocally || !_confirmedRemotely) return;

        Result = new PairedPeer(
            _offer!.DeviceId,
            _verifiedFingerprint!,
            _offer.Name,
            DateTimeOffset.UtcNow);

        State = PairingState.Paired;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PairingSessionTests`
Expected: PASS, 12 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Pairing/PairingSession.cs windows/tests/Slipstream.Core.Tests/Pairing/PairingSessionTests.cs
git commit -m "feat: add pairing session with certificate-bound code and mutual confirm"
```

---

## Task 3: Window-gated unpaired accept path

The security-critical change. Read Plan 1's `ControlServer.HandleAsync` before touching it.

**Files:**
- Modify: `windows/src/Slipstream.Core/Control/ControlServer.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Pairing/PairingAcceptPathTests.cs`

**Interfaces:**
- Produces, added to `ControlServer`:
  - `ControlServer(DeviceIdentity identity, PairedPeerStore peers, IPAddress bindAddress, int port, PairingWindow? pairingWindow = null)` — the optional parameter keeps every existing call site compiling unchanged.
  - `event Func<ControlConnection, CancellationToken, Task>? PairingConnected` — raised **only** for connections accepted through the pairing path, and never `PeerConnected`.
- Behaviour: an untrusted fingerprint is accepted **only** when a pairing window was supplied and is open; otherwise the connection is dropped exactly as today.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Pairing/PairingAcceptPathTests.cs`:

```csharp
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingAcceptPathTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-pairaccept-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(20));

    private readonly DeviceIdentity _server = DeviceIdentity.CreateNew("Server PC");
    private readonly DeviceIdentity _stranger = DeviceIdentity.CreateNew("Stranger");

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    /// <summary>Connects as an unpaired stranger: TLS, but no pin to check against.</summary>
    private async Task<ControlConnection?> ConnectUnpairedAsync(IPEndPoint endpoint)
    {
        var tcp = new TcpClient { NoDelay = true };
        try
        {
            await tcp.ConnectAsync(endpoint, _cts.Token);

            var stream = await PinnedTls.AuthenticateAsClientAsync(
                tcp.GetStream(), _stranger, acceptFingerprint: _ => true, _cts.Token);

            return new ControlConnection(stream, PinnedTls.FingerprintOf(stream), endpoint);
        }
        catch
        {
            tcp.Dispose();
            return null;
        }
    }

    [Fact]
    public async Task With_the_window_closed_an_unpaired_connection_is_still_dropped()
    {
        // Plan 1's guarantee, unchanged. This is the test that must never go green
        // for the wrong reason.
        var window = new PairingWindow(); // never opened
        var pairingRaised = false;
        var peerRaised = false;

        await using var server = new ControlServer(
            _server, new PairedPeerStore(_dir), IPAddress.Loopback, 0, window);

        server.PairingConnected += (_, _) => { pairingRaised = true; return Task.CompletedTask; };
        server.PeerConnected += (_, _) => { peerRaised = true; return Task.CompletedTask; };

        _ = server.RunAsync(_cts.Token);

        var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        if (connection is not null)
        {
            await connection.SendAsync(ControlMessage.Request("pair.offer", "1"), _cts.Token);
            Assert.Null(await connection.ReceiveAsync(_cts.Token)); // stream closed
            await connection.DisposeAsync();
        }

        Assert.False(pairingRaised);
        Assert.False(peerRaised);
    }

    [Fact]
    public async Task With_no_window_supplied_at_all_an_unpaired_connection_is_dropped()
    {
        var pairingRaised = false;

        await using var server = new ControlServer(
            _server, new PairedPeerStore(_dir), IPAddress.Loopback, 0); // no window argument

        server.PairingConnected += (_, _) => { pairingRaised = true; return Task.CompletedTask; };
        _ = server.RunAsync(_cts.Token);

        var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        if (connection is not null) await connection.DisposeAsync();

        await Task.Delay(300, _cts.Token);
        Assert.False(pairingRaised);
    }

    [Fact]
    public async Task With_the_window_open_an_unpaired_connection_reaches_the_pairing_handler()
    {
        var window = new PairingWindow();
        window.Open();

        var pairingFingerprint = new TaskCompletionSource<string>();

        await using var server = new ControlServer(
            _server, new PairedPeerStore(_dir), IPAddress.Loopback, 0, window);

        server.PairingConnected += (connection, _) =>
        {
            pairingFingerprint.TrySetResult(connection.PeerFingerprint);
            return Task.CompletedTask;
        };

        _ = server.RunAsync(_cts.Token);

        await using var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        Assert.NotNull(connection);

        var seen = await pairingFingerprint.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);
        Assert.Equal(_stranger.Fingerprint, seen);
    }

    [Fact]
    public async Task An_unpaired_connection_never_reaches_the_normal_peer_handler()
    {
        // The restricted path must not leak into browse/transfer.
        var window = new PairingWindow();
        window.Open();

        var peerRaised = false;
        var pairingRaised = new TaskCompletionSource();

        await using var server = new ControlServer(
            _server, new PairedPeerStore(_dir), IPAddress.Loopback, 0, window);

        server.PeerConnected += (_, _) => { peerRaised = true; return Task.CompletedTask; };
        server.PairingConnected += (_, _) => { pairingRaised.TrySetResult(); return Task.CompletedTask; };

        _ = server.RunAsync(_cts.Token);

        await using var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        await pairingRaised.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);

        Assert.False(peerRaised);
    }

    [Fact]
    public async Task An_already_paired_peer_still_reaches_the_normal_handler_while_the_window_is_open()
    {
        var window = new PairingWindow();
        window.Open();

        var peers = new PairedPeerStore(_dir);
        peers.Pair(new PairedPeer(_stranger.DeviceId, _stranger.Fingerprint, "Stranger", DateTimeOffset.UtcNow));

        var peerRaised = new TaskCompletionSource();
        var pairingRaised = false;

        await using var server = new ControlServer(_server, peers, IPAddress.Loopback, 0, window);

        server.PeerConnected += (_, _) => { peerRaised.TrySetResult(); return Task.CompletedTask; };
        server.PairingConnected += (_, _) => { pairingRaised = true; return Task.CompletedTask; };

        _ = server.RunAsync(_cts.Token);

        await using var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        await peerRaised.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);

        Assert.False(pairingRaised);
    }

    [Fact]
    public async Task An_expired_window_drops_unpaired_connections_again()
    {
        var window = new PairingWindow();
        window.Open();
        window.Close(); // simulates expiry from the accept path's point of view

        var pairingRaised = false;

        await using var server = new ControlServer(
            _server, new PairedPeerStore(_dir), IPAddress.Loopback, 0, window);

        server.PairingConnected += (_, _) => { pairingRaised = true; return Task.CompletedTask; };
        _ = server.RunAsync(_cts.Token);

        var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        if (connection is not null) await connection.DisposeAsync();

        await Task.Delay(300, _cts.Token);
        Assert.False(pairingRaised);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PairingAcceptPathTests`
Expected: FAIL — `ControlServer` has no `PairingConnected` and no window parameter.

- [ ] **Step 3: Modify `ControlServer`**

In `windows/src/Slipstream.Core/Control/ControlServer.cs`:

Change the constructor to accept an optional window, storing it in a field:

```csharp
private readonly PairingWindow? _pairingWindow;

public ControlServer(
    DeviceIdentity identity,
    PairedPeerStore peers,
    IPAddress bindAddress,
    int port,
    PairingWindow? pairingWindow = null)
{
    LanGuard.EnsureLocal(bindAddress);

    _identity = identity;
    _peers = peers;
    _pairingWindow = pairingWindow;
    _listener = new TcpListener(bindAddress, port);
    _listener.Start();
}
```

Add the event beside `PeerConnected`:

```csharp
/// <summary>
/// Raised only for connections accepted through the pairing path — an unpaired peer
/// during an open pairing window. These connections may speak `pair.*` and nothing
/// else; they must never be handed to the browse/transfer session.
/// </summary>
public event Func<ControlConnection, CancellationToken, Task>? PairingConnected;
```

Replace the trust check in `HandleAsync`. The existing block is:

```csharp
if (!_peers.Trusts(fingerprint))
{
    // Unpaired devices get nothing. No prompt, no override path.
    await stream.DisposeAsync();
    client.Dispose();
    return;
}

await using var connection = new ControlConnection(stream, fingerprint, remote);

var handler = PeerConnected;
if (handler is not null) await handler(connection, cancellationToken);
```

with:

```csharp
var trusted = _peers.Trusts(fingerprint);

// Unpaired devices get nothing — unless the user has deliberately opened a
// pairing window, in which case they reach the restricted pairing handler only.
if (!trusted && _pairingWindow?.IsOpen != true)
{
    await stream.DisposeAsync();
    client.Dispose();
    return;
}

await using var connection = new ControlConnection(stream, fingerprint, remote);

var handler = trusted ? PeerConnected : PairingConnected;
if (handler is not null) await handler(connection, cancellationToken);
```

Add `using Slipstream.Core.Pairing;`.

Note the ordering: `trusted` is evaluated first, so an **already-paired** peer takes the normal path even while a window is open. Routing a trusted peer into the pairing handler would be a functional regression, and the fifth test pins it.

- [ ] **Step 4: Run the new tests and Plan 1's**

```bash
dotnet test windows/Slipstream.sln --filter PairingAcceptPathTests
dotnet test windows/Slipstream.sln --filter PairingHandshakeTests
```

Expected: both PASS. Plan 1's `Server_drops_a_connection_from_an_untrusted_fingerprint` must pass **unmodified** — if it needed changing, the gate is wrong.

- [ ] **Step 5: Run the whole suite**

Run: `dotnet test windows/Slipstream.sln`
Expected: PASS — all 99 pre-existing tests plus the new ones.

- [ ] **Step 6: Commit**

```bash
git add windows/src/Slipstream.Core/Control/ControlServer.cs windows/tests/Slipstream.Core.Tests/Pairing/PairingAcceptPathTests.cs
git commit -m "feat: accept unpaired connections only through an open pairing window"
```

---

## Task 4: `ConnectForPairingAsync`

**Files:**
- Modify: `windows/src/Slipstream.Core/Control/ControlClient.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Pairing/PairingClientTests.cs`

**Interfaces:**
- Produces: `Task<ControlConnection?> ConnectForPairingAsync(IPEndPoint endpoint, TimeSpan timeout, CancellationToken ct)` — connects with TLS but no pin, since there is nothing to pin against yet. Still enforces `LanGuard`. Unlike `ConnectAsync` it does **not** require `peers.IsPaired`.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Pairing/PairingClientTests.cs`:

```csharp
using System.Net;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingClientTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-pairclient-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(20));

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    [Fact]
    public async Task Connects_while_completely_unpaired()
    {
        // ConnectAsync refuses when unpaired. The pairing path must not.
        var serverIdentity = DeviceIdentity.CreateNew("Server PC");
        var clientIdentity = DeviceIdentity.CreateNew("Client Phone");

        var window = new PairingWindow();
        window.Open();

        await using var server = new ControlServer(
            serverIdentity, new PairedPeerStore(Path.Combine(_dir, "srv")), IPAddress.Loopback, 0, window);

        var reached = new TaskCompletionSource();
        server.PairingConnected += (_, _) => { reached.TrySetResult(); return Task.CompletedTask; };
        _ = server.RunAsync(_cts.Token);

        var client = new ControlClient(clientIdentity, new PairedPeerStore(Path.Combine(_dir, "cli")));

        await using var connection = await client.ConnectForPairingAsync(
            server.ListenEndPoint, TimeSpan.FromSeconds(10), _cts.Token);

        Assert.NotNull(connection);
        Assert.Equal(serverIdentity.Fingerprint, connection.PeerFingerprint);

        await reached.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);
    }

    [Fact]
    public async Task Reports_the_servers_real_certificate_fingerprint()
    {
        var serverIdentity = DeviceIdentity.CreateNew("Server PC");

        var window = new PairingWindow();
        window.Open();

        await using var server = new ControlServer(
            serverIdentity, new PairedPeerStore(Path.Combine(_dir, "srv2")), IPAddress.Loopback, 0, window);
        _ = server.RunAsync(_cts.Token);

        var client = new ControlClient(
            DeviceIdentity.CreateNew("Client"), new PairedPeerStore(Path.Combine(_dir, "cli2")));

        await using var connection = await client.ConnectForPairingAsync(
            server.ListenEndPoint, TimeSpan.FromSeconds(10), _cts.Token);

        // This is what the code is derived from — it must be the certificate, not a claim.
        Assert.Equal(serverIdentity.Fingerprint, connection!.PeerFingerprint);
    }

    [Fact]
    public async Task Refuses_a_non_local_endpoint()
    {
        var client = new ControlClient(
            DeviceIdentity.CreateNew("Client"), new PairedPeerStore(Path.Combine(_dir, "cli3")));

        await Assert.ThrowsAsync<NonLocalAddressException>(() =>
            client.ConnectForPairingAsync(
                new IPEndPoint(IPAddress.Parse("8.8.8.8"), 53321), TimeSpan.FromSeconds(2), _cts.Token));
    }

    [Fact]
    public async Task Returns_null_when_nothing_is_listening()
    {
        var client = new ControlClient(
            DeviceIdentity.CreateNew("Client"), new PairedPeerStore(Path.Combine(_dir, "cli4")));

        var connection = await client.ConnectForPairingAsync(
            new IPEndPoint(IPAddress.Loopback, 1), TimeSpan.FromMilliseconds(500), _cts.Token);

        Assert.Null(connection);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PairingClientTests`
Expected: FAIL — `ConnectForPairingAsync` does not exist.

- [ ] **Step 3: Add the method to `ControlClient`**

```csharp
/// <summary>
/// Connects for pairing: TLS, but the certificate is not pinned — there is nothing to
/// pin against until pairing completes. The resulting <see cref="ControlConnection.PeerFingerprint"/>
/// is the peer's real certificate fingerprint and is what the six-digit code is derived
/// from. LanGuard still applies; plaintext pairing is never permitted.
/// </summary>
public async Task<ControlConnection?> ConnectForPairingAsync(
    IPEndPoint endpoint,
    TimeSpan timeout,
    CancellationToken cancellationToken)
{
    LanGuard.EnsureLocal(endpoint.Address);

    using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
    linked.CancelAfter(timeout);

    var tcp = new TcpClient();
    try
    {
        tcp.NoDelay = true;
        await tcp.ConnectAsync(endpoint, linked.Token);

        var stream = await PinnedTls.AuthenticateAsClientAsync(
            tcp.GetStream(), identity, acceptFingerprint: _ => true, linked.Token);

        return new ControlConnection(stream, PinnedTls.FingerprintOf(stream), endpoint);
    }
    catch (NonLocalAddressException)
    {
        tcp.Dispose();
        throw;
    }
    catch
    {
        // Unreachable, refused, or the peer closed the window. Not an error here.
        tcp.Dispose();
        return null;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PairingClientTests`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Control/ControlClient.cs windows/tests/Slipstream.Core.Tests/Pairing/PairingClientTests.cs
git commit -m "feat: add unpinned pairing connect path"
```

---

## Task 5: Unpaired peer discovery

**Files:**
- Create: `windows/src/Slipstream.Core/Pairing/PairingDiscovery.cs`
- Modify: `windows/src/Slipstream.Core/Discovery/MulticastStrategy.cs` — expose the existing fan-out to unpaired subscribers
- Test: `windows/tests/Slipstream.Core.Tests/Pairing/PairingDiscoveryTests.cs`

**Interfaces:**
- Produces:
  - `sealed record UnpairedPeer(string DeviceId, string DisplayName, string Fingerprint, IPEndPoint Endpoint)`
  - `sealed class PairingDiscovery { PairingDiscovery(DeviceIdentity identity, MulticastStrategy multicast, PairingWindow window); Task<UnpairedPeer?> FindAsync(TimeSpan timeout, CancellationToken ct); }`
  - Returns `null` immediately when the window is closed.
- On `MulticastStrategy`, add `IAsyncEnumerable<(PeerAnnouncement Announcement, IPEndPoint Source)> SubscribeAsync(CancellationToken ct)` fed by the **existing** single reader loop.

> **Read `MulticastStrategy.cs` in full before editing.** Plan 1's final review replaced two concurrent `ReceiveAsync` loops with one reader that fans datagrams out to the responder and to per-call `Channel<>` subscribers. Hook into that fan-out. **Do not add a second `ReceiveAsync` on that socket** — that is precisely the Critical bug Plan 1 shipped and fixed.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Pairing/PairingDiscoveryTests.cs`:

```csharp
using System.Net;
using System.Net.Sockets;
using Slipstream.Core;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingDiscoveryTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-pairdisc-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(25));

    private readonly DeviceIdentity _local = DeviceIdentity.CreateNew("Local PC");

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    private MulticastStrategy NewMulticast() =>
        new(_local, new PairedPeerStore(_dir), (_, _) => Task.FromResult<DiscoveredPeer?>(null), listenPort: 0);

    private static async Task SendAsync(IPEndPoint target, PeerAnnouncement announcement)
    {
        await Task.Delay(250);
        using var sender = new UdpClient(AddressFamily.InterNetwork);
        await sender.SendAsync(announcement.ToBytes(), target);
    }

    private static PeerAnnouncement Announcement(string deviceId, string name, string fingerprint) =>
        new(SlipstreamPorts.ProtocolVersion, deviceId, name, fingerprint,
            SlipstreamPorts.Control, AnnouncementKind.Announce);

    [Fact]
    public async Task Returns_null_immediately_when_the_window_is_closed()
    {
        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, new PairingWindow());

        var found = await discovery.FindAsync(TimeSpan.FromSeconds(5), _cts.Token);

        Assert.Null(found);
    }

    [Fact]
    public async Task Finds_an_unpaired_peer_while_the_window_is_open()
    {
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window);

        var find = discovery.FindAsync(TimeSpan.FromSeconds(15), _cts.Token);
        await SendAsync(multicast.ListenEndPoint, Announcement("stranger-id", "Stranger Phone", "cafebabe"));

        var found = await find;

        Assert.NotNull(found);
        Assert.Equal("stranger-id", found.DeviceId);
        Assert.Equal("Stranger Phone", found.DisplayName);
        Assert.Equal("cafebabe", found.Fingerprint);
        Assert.Equal(SlipstreamPorts.Control, found.Endpoint.Port);
    }

    [Fact]
    public async Task Ignores_our_own_announcement()
    {
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window);

        var find = discovery.FindAsync(TimeSpan.FromSeconds(4), _cts.Token);
        await SendAsync(multicast.ListenEndPoint,
            Announcement(_local.DeviceId, _local.DisplayName, _local.Fingerprint));

        Assert.Null(await find);
    }

    [Fact]
    public async Task Returns_null_when_nothing_announces_before_the_timeout()
    {
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window);

        Assert.Null(await discovery.FindAsync(TimeSpan.FromSeconds(2), _cts.Token));
    }

    [Fact]
    public async Task Ignores_malformed_datagrams_and_keeps_looking()
    {
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window);

        var find = discovery.FindAsync(TimeSpan.FromSeconds(15), _cts.Token);

        await Task.Delay(250, _cts.Token);
        using (var junk = new UdpClient(AddressFamily.InterNetwork))
        {
            await junk.SendAsync("garbage"u8.ToArray(), multicast.ListenEndPoint);
        }

        await SendAsync(multicast.ListenEndPoint, Announcement("stranger-id", "Stranger", "cafebabe"));

        Assert.NotNull(await find);
    }

    [Fact]
    public async Task The_paired_discovery_path_still_works_alongside_a_pairing_subscription()
    {
        // Regression guard for the bug Plan 1 fixed: adding a subscriber must not
        // starve the existing responder or FindAsync paths.
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window);

        var pairingFind = discovery.FindAsync(TimeSpan.FromSeconds(15), _cts.Token);
        var responder = multicast.RespondToQueriesAsync(_cts.Token);

        await SendAsync(multicast.ListenEndPoint, Announcement("stranger-id", "Stranger", "cafebabe"));

        Assert.NotNull(await pairingFind);
        Assert.False(responder.IsFaulted);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PairingDiscoveryTests`
Expected: FAIL — `PairingDiscovery` does not exist.

- [ ] **Step 3: Expose the fan-out on `MulticastStrategy`**

Read the file, then add a subscriber method that reuses the existing `Channel<>` fan-out the reader loop already writes to. The exact shape depends on how Plan 1's fix was written; the requirement is:

- One new `public IAsyncEnumerable<(PeerAnnouncement Announcement, IPEndPoint Source)> SubscribeAsync(CancellationToken ct)`.
- It registers a channel with the existing fan-out and unregisters on disposal or cancellation.
- **No new `ReceiveAsync` call anywhere.**
- Announcements are delivered to pairing subscribers **regardless of trust** — filtering by trust is `FindAsync`'s job, not the reader's.

If the existing fan-out only forwards trusted announcements, move that trust filter out of the reader and into `FindAsync`'s subscriber, so both consumers see every datagram and each applies its own policy. Add a comment saying why.

- [ ] **Step 4: Write `PairingDiscovery`**

Create `windows/src/Slipstream.Core/Pairing/PairingDiscovery.cs`:

```csharp
using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Pairing;

public sealed record UnpairedPeer(
    string DeviceId, string DisplayName, string Fingerprint, IPEndPoint Endpoint);

/// <summary>
/// Finds a device that is not yet paired with us. Only ever active inside an open
/// pairing window — outside it this returns null without listening at all.
/// </summary>
public sealed class PairingDiscovery(
    DeviceIdentity identity,
    MulticastStrategy multicast,
    PairingWindow window)
{
    public async Task<UnpairedPeer?> FindAsync(TimeSpan timeout, CancellationToken cancellationToken)
    {
        if (!window.IsOpen) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        linked.CancelAfter(timeout);

        try
        {
            await foreach (var (announcement, source) in multicast.SubscribeAsync(linked.Token))
            {
                if (!window.IsOpen) return null;

                // Never discover ourselves.
                if (string.Equals(announcement.Fingerprint, identity.Fingerprint, StringComparison.OrdinalIgnoreCase))
                    continue;

                if (!LanGuard.IsLocal(source.Address)) continue;

                return new UnpairedPeer(
                    announcement.DeviceId,
                    announcement.DisplayName,
                    announcement.Fingerprint,
                    new IPEndPoint(source.Address, announcement.ControlPort));
            }
        }
        catch (OperationCanceledException)
        {
            // Timed out, or the caller gave up.
        }

        return null;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PairingDiscoveryTests`
Expected: PASS, 6 tests.

- [ ] **Step 6: Run the whole suite, especially the multicast regression test**

Run: `dotnet test windows/Slipstream.sln`
Expected: PASS — including the race regression test Plan 1 added. If it goes red, the fan-out change re-introduced the bug; revert and reconsider rather than adjusting that test.

- [ ] **Step 7: Commit**

```bash
git add windows/src/Slipstream.Core/Pairing/PairingDiscovery.cs windows/src/Slipstream.Core/Discovery/MulticastStrategy.cs windows/tests/Slipstream.Core.Tests/Pairing/PairingDiscoveryTests.cs
git commit -m "feat: discover unpaired peers through the existing multicast fan-out"
```

---

## Task 6: The pairing coordinator

**Files:**
- Create: `windows/src/Slipstream.Core/Pairing/PairingCoordinator.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Pairing/PairingCoordinatorTests.cs`

**Interfaces:**
- Produces:
  - `sealed record PairingProgress(PairingState State, string? Code, string? PeerName)`
  - `sealed class PairingCoordinator { PairingCoordinator(DeviceIdentity identity, PairedPeerStore peers, ControlClient client, PairingWindow window); Task<PairedPeer?> PairAsync(ControlConnection connection, bool isInitiator, Func<string, CancellationToken, Task<bool>> confirmCode, IProgress<PairingProgress>? progress, CancellationToken ct); }`
  - `confirmCode` is the UI hook: it receives the six digits and returns the user's decision.
  - On success the peer is persisted via `peers.Pair(...)` and the window is closed.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Pairing/PairingCoordinatorTests.cs`:

```csharp
using System.Net;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingCoordinatorTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-paircoord-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(30));

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    /// <summary>Two cold, never-paired instances wired together over loopback.</summary>
    private sealed record Rig(
        DeviceIdentity ServerIdentity, PairedPeerStore ServerPeers, PairingWindow ServerWindow,
        PairingCoordinator ServerCoordinator, ControlServer Server,
        DeviceIdentity ClientIdentity, PairedPeerStore ClientPeers, PairingWindow ClientWindow,
        PairingCoordinator ClientCoordinator, ControlClient Client);

    private Rig BuildRig()
    {
        var serverIdentity = DeviceIdentity.CreateNew("Server PC");
        var clientIdentity = DeviceIdentity.CreateNew("Client Phone");

        var serverPeers = new PairedPeerStore(Path.Combine(_dir, "srv"));
        var clientPeers = new PairedPeerStore(Path.Combine(_dir, "cli"));

        var serverWindow = new PairingWindow();
        var clientWindow = new PairingWindow();
        serverWindow.Open();
        clientWindow.Open();

        var serverClient = new ControlClient(serverIdentity, serverPeers);
        var client = new ControlClient(clientIdentity, clientPeers);

        var server = new ControlServer(
            serverIdentity, serverPeers, IPAddress.Loopback, 0, serverWindow);

        return new Rig(
            serverIdentity, serverPeers, serverWindow,
            new PairingCoordinator(serverIdentity, serverPeers, serverClient, serverWindow), server,
            clientIdentity, clientPeers, clientWindow,
            new PairingCoordinator(clientIdentity, clientPeers, client, clientWindow), client);
    }

    private static Func<string, CancellationToken, Task<bool>> Accepts(List<string> seen) =>
        (code, _) => { lock (seen) seen.Add(code); return Task.FromResult(true); };

    private static Func<string, CancellationToken, Task<bool>> Declines() =>
        (_, _) => Task.FromResult(false);

    /// <summary>Runs both halves and returns (serverResult, clientResult).</summary>
    private async Task<(PairedPeer?, PairedPeer?)> RunAsync(
        Rig rig,
        Func<string, CancellationToken, Task<bool>> serverConfirm,
        Func<string, CancellationToken, Task<bool>> clientConfirm)
    {
        PairedPeer? serverResult = null;

        var accepted = new TaskCompletionSource();
        rig.Server.PairingConnected += async (connection, token) =>
        {
            accepted.TrySetResult();
            serverResult = await rig.ServerCoordinator.PairAsync(
                connection, isInitiator: false, serverConfirm, null, token);
        };

        _ = rig.Server.RunAsync(_cts.Token);

        await using var connection = await rig.Client.ConnectForPairingAsync(
            rig.Server.ListenEndPoint, TimeSpan.FromSeconds(10), _cts.Token);

        var clientResult = await rig.ClientCoordinator.PairAsync(
            connection!, isInitiator: true, clientConfirm, null, _cts.Token);

        await accepted.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);
        await Task.Delay(500, _cts.Token); // let the server half settle

        return (serverResult, clientResult);
    }

    [Fact]
    public async Task Two_cold_devices_reach_a_mutual_paired_state()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        var (serverResult, clientResult) = await RunAsync(rig, Accepts([]), Accepts([]));

        Assert.NotNull(serverResult);
        Assert.NotNull(clientResult);

        Assert.True(rig.ServerPeers.IsPaired);
        Assert.True(rig.ClientPeers.IsPaired);

        Assert.True(rig.ServerPeers.Trusts(rig.ClientIdentity.Fingerprint));
        Assert.True(rig.ClientPeers.Trusts(rig.ServerIdentity.Fingerprint));
    }

    [Fact]
    public async Task Both_users_are_shown_the_same_six_digit_code()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        var serverCodes = new List<string>();
        var clientCodes = new List<string>();

        await RunAsync(rig, Accepts(serverCodes), Accepts(clientCodes));

        Assert.Single(serverCodes);
        Assert.Single(clientCodes);
        Assert.Equal(serverCodes[0], clientCodes[0]);
        Assert.Matches("^[0-9]{6}$", serverCodes[0]);
    }

    [Fact]
    public async Task The_persisted_peer_carries_the_other_devices_display_name()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        await RunAsync(rig, Accepts([]), Accepts([]));

        Assert.Equal("Client Phone", rig.ServerPeers.Current!.DisplayName);
        Assert.Equal("Server PC", rig.ClientPeers.Current!.DisplayName);
    }

    [Fact]
    public async Task Neither_side_pairs_when_one_user_declines()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        var (serverResult, clientResult) = await RunAsync(rig, Declines(), Accepts([]));

        Assert.Null(serverResult);
        Assert.Null(clientResult);
        Assert.False(rig.ServerPeers.IsPaired);
        Assert.False(rig.ClientPeers.IsPaired);
    }

    [Fact]
    public async Task Neither_side_pairs_when_the_initiator_declines()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        var (serverResult, clientResult) = await RunAsync(rig, Accepts([]), Declines());

        Assert.Null(serverResult);
        Assert.Null(clientResult);
        Assert.False(rig.ServerPeers.IsPaired);
        Assert.False(rig.ClientPeers.IsPaired);
    }

    [Fact]
    public async Task A_successful_pairing_closes_both_windows()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        await RunAsync(rig, Accepts([]), Accepts([]));

        Assert.False(rig.ServerWindow.IsOpen);
        Assert.False(rig.ClientWindow.IsOpen);
    }

    [Fact]
    public async Task After_pairing_the_normal_pinned_connect_path_works()
    {
        // The real acceptance criterion: pairing is only useful if it produces a
        // state the rest of the app can actually use.
        var rig = BuildRig();
        await using var _ = rig.Server;

        await RunAsync(rig, Accepts([]), Accepts([]));

        var reached = new TaskCompletionSource();
        rig.Server.PeerConnected += (_, _) => { reached.TrySetResult(); return Task.CompletedTask; };

        await using var connection = await rig.Client.ConnectAsync(
            rig.Server.ListenEndPoint, TimeSpan.FromSeconds(10), _cts.Token);

        Assert.NotNull(connection);
        await reached.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);
    }

    [Fact]
    public async Task Pairing_replaces_an_existing_peer()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        rig.ClientPeers.Pair(new PairedPeer("old-device", "oldfingerprint", "Old PC", DateTimeOffset.UtcNow));

        await RunAsync(rig, Accepts([]), Accepts([]));

        Assert.False(rig.ClientPeers.Trusts("oldfingerprint"));
        Assert.True(rig.ClientPeers.Trusts(rig.ServerIdentity.Fingerprint));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PairingCoordinatorTests`
Expected: FAIL — `PairingCoordinator` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Pairing/PairingCoordinator.cs`:

```csharp
using Slipstream.Core.Control;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Pairing;

public sealed record PairingProgress(PairingState State, string? Code, string? PeerName);

/// <summary>
/// Drives one pairing attempt to completion over an already-established, unpinned
/// connection. Symmetric: both devices run this, differing only in who sends the
/// first offer.
///
/// The wire flow is deliberately tiny — offer, offer, confirm, confirm — because
/// this is the one code path an unpaired stranger can reach.
/// </summary>
public sealed class PairingCoordinator(
    DeviceIdentity identity,
    PairedPeerStore peers,
    ControlClient client,
    PairingWindow window)
{
    public async Task<PairedPeer?> PairAsync(
        ControlConnection connection,
        bool isInitiator,
        Func<string, CancellationToken, Task<bool>> confirmCode,
        IProgress<PairingProgress>? progress,
        CancellationToken cancellationToken)
    {
        var session = new PairingSession(identity);

        var offer = new PairOfferPayload(
            SlipstreamPorts.ProtocolVersion, identity.DeviceId, identity.DisplayName, identity.Fingerprint);

        if (isInitiator)
            await connection.SendAsync(ControlMessage.Request("pair.offer", "1", offer), cancellationToken);

        var confirmSent = false;

        while (!cancellationToken.IsCancellationRequested)
        {
            var message = await connection.ReceiveAsync(cancellationToken);
            if (message is null) break; // peer closed

            switch (message.Type)
            {
                case "pair.offer":
                {
                    var payload = message.PayloadAs<PairOfferPayload>();
                    if (payload is null) return Fail(session, progress);

                    // The certificate fingerprint, never the payload's claim.
                    session.ReceiveOffer(payload, connection.PeerFingerprint);

                    if (session.State == PairingState.Cancelled) return Fail(session, progress);

                    if (!isInitiator)
                        await connection.SendAsync(ControlMessage.Request("pair.offer", "1", offer), cancellationToken);

                    progress?.Report(new PairingProgress(session.State, session.Code, payload.Name));

                    // Ask the user. This blocks the flow, deliberately — the whole
                    // security argument rests on a human comparing two numbers.
                    var accepted = await confirmCode(session.Code!, cancellationToken);

                    if (!accepted)
                    {
                        await connection.SendAsync(ControlMessage.Event("pair.cancel"), cancellationToken);
                        return Fail(session, progress);
                    }

                    session.ConfirmLocally();
                    await connection.SendAsync(ControlMessage.Event("pair.confirm"), cancellationToken);
                    confirmSent = true;
                    break;
                }

                case "pair.confirm":
                    session.ReceiveRemoteConfirm();
                    break;

                case "pair.cancel":
                    return Fail(session, progress);

                default:
                    // Restricted path: nothing else is answerable here.
                    continue;
            }

            if (session.State == PairingState.Paired)
            {
                peers.Pair(session.Result!);
                window.Close();

                progress?.Report(new PairingProgress(PairingState.Paired, session.Code, session.Result!.DisplayName));
                return session.Result;
            }
        }

        // The peer hung up. If we confirmed and they never did, that is a decline.
        _ = confirmSent;
        return Fail(session, progress);
    }

    private static PairedPeer? Fail(PairingSession session, IProgress<PairingProgress>? progress)
    {
        session.Cancel();
        progress?.Report(new PairingProgress(PairingState.Cancelled, session.Code, null));
        return null;
    }
}
```

**Implementation note.** If the mutual-confirm exchange deadlocks — both sides waiting on the other's `pair.confirm` before returning — the cause is the receive loop exiting before the second confirm arrives. The fix is to keep looping until `Paired`, `Cancelled`, or the connection closes, which the code above does. Do not "fix" it by pairing on a single confirmation; that removes the mutual-confirmation guarantee the whole design rests on.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PairingCoordinatorTests`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Pairing/PairingCoordinator.cs windows/tests/Slipstream.Core.Tests/Pairing/PairingCoordinatorTests.cs
git commit -m "feat: add pairing coordinator with mutual confirmation"
```

---

## Task 7: Peer wire-up, harness, protocol doc, and spec path fix

**Files:**
- Modify: `windows/src/Slipstream.Core/SlipstreamPeer.cs`
- Modify: `windows/tools/Slipstream.Harness/Program.cs`
- Create: `protocol/pairing.md`
- Modify: `docs/superpowers/specs/2026-08-25-slipstream-design.md` — §18 path correction
- Modify: `docs/superpowers/plans/2026-08-25-core-discovery-control-deviations.md` — mark two gaps closed

**Interfaces:**
- Produces, added to `SlipstreamPeer`: `PairingWindow Pairing { get; }`, `PairingDiscovery PairingDiscovery { get; }`, `Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>> confirmCode, TimeSpan timeout, CancellationToken ct)`.
- `StartAsync` passes the window into `ControlServer` and wires `PairingConnected` to a `PairingCoordinator`.

- [ ] **Step 1: Wire the peer**

In `SlipstreamPeer`, add fields for `PairingWindow`, `PairingCoordinator`, and `PairingDiscovery`, construct them in the constructor, and pass the window to `ControlServer` in `StartAsync`:

```csharp
_server = new ControlServer(Identity, Peers, network.LocalAddress, SlipstreamPorts.Control, Pairing);

_server.PairingConnected += async (connection, token) =>
{
    if (_confirmCode is null) return; // no pairing attempt in flight
    await _coordinator.PairAsync(connection, isInitiator: false, _confirmCode, null, token);
};
```

`_confirmCode` is set for the duration of `PairAsync` and cleared afterwards, so an inbound pairing connection is only answered while this device is actively pairing.

`PairAsync` opens the window, runs discovery, connects with `ConnectForPairingAsync`, drives the coordinator as initiator, and closes the window in a `finally`.

- [ ] **Step 2: Add harness commands**

Add to `windows/tools/Slipstream.Harness/Program.cs`:

```csharp
case "pair-mode":
{
    // pair-mode <state> — opens a 120s window and pairs with whoever answers.
    await using var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
    _ = peer.StartAsync(cts.Token);
    await Task.Delay(300, cts.Token);

    Console.WriteLine($"This device: {peer.Identity.DisplayName}  {peer.Identity.Fingerprint[..16]}…");
    Console.WriteLine("Pairing window open for 120 seconds. Run 'pair-mode' on the other device too.");

    var result = await peer.PairAsync(
        confirmCode: (code, _) =>
        {
            Console.WriteLine();
            Console.WriteLine($"    Pairing code:  {code}");
            Console.WriteLine();
            Console.Write("Does this match the code on the other device? [y/N] ");
            var answer = Console.ReadLine();
            return Task.FromResult(answer?.Trim().Equals("y", StringComparison.OrdinalIgnoreCase) == true);
        },
        timeout: TimeSpan.FromSeconds(120),
        cts.Token);

    Console.WriteLine(result is null
        ? "Pairing did not complete."
        : $"Paired with {result.DisplayName}.");

    return result is null ? 1 : 0;
}

case "paired":
{
    var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
    Console.WriteLine(peer.Peers.Current is { } current
        ? $"Paired with {current.DisplayName} ({current.DeviceId}) since {current.PairedAt:u}"
        : "Not paired.");
    break;
}
```

- [ ] **Step 3: Verify two cold instances pair**

```bash
cd windows
rm -rf "$TEMP/slipstream-harness/alice" "$TEMP/slipstream-harness/bob"
```

Then in two terminals:

```bash
dotnet run --project tools/Slipstream.Harness -- pair-mode alice
```

```bash
dotnet run --project tools/Slipstream.Harness -- pair-mode bob
```

Expected: both print the **same** six digits; answering `y` on both pairs them. Then confirm the result is usable:

```bash
dotnet run --project tools/Slipstream.Harness -- paired alice
dotnet run --project tools/Slipstream.Harness -- serve alice
dotnet run --project tools/Slipstream.Harness -- find bob
```

`find` must locate the peer and complete `hello.ok` — pairing is only worth anything if it produces a state the rest of the app can use.

Also verify the negative case: answer `n` on one side and confirm neither device reports paired.

- [ ] **Step 4: Write the protocol document**

Create `protocol/pairing.md`: the four message types with example JSON lines, the code-derivation rule (already vectored in `protocol/vectors/pairing-codes.json`), the window semantics, the restricted-handler rule, and the threat model section from the top of this plan verbatim. Link it from `protocol/protocol.md`.

- [ ] **Step 5: Fix the spec path inconsistency**

In `docs/superpowers/specs/2026-08-25-slipstream-design.md` §18, change the repository-structure entry for the wire spec from `docs/protocol.md` to `protocol/protocol.md`, matching the delivered layout, and add `protocol/pairing.md` and `protocol/bulk-format.md` beside it. The delivered layout is the better one — the spec is what moves.

- [ ] **Step 6: Update the deviations record**

In `docs/superpowers/plans/2026-08-25-core-discovery-control-deviations.md`, mark two carried-forward gaps closed, with links to this plan:

- *"`protocol.md` lives at `protocol/protocol.md`"* → closed; spec §18 corrected.
- *"No code path yet bootstraps a first pairing"* → closed by Plan 1b.

Leave *"Cross-machine discovery-matrix verification not performed"* open — this plan does not address it.

- [ ] **Step 7: Run everything**

Run: `dotnet test windows/Slipstream.sln`
Expected: PASS — all pre-existing tests plus this plan's.

- [ ] **Step 8: Commit**

```bash
git add windows protocol docs
git commit -m "feat: wire pairing into the peer, add harness commands and protocol doc"
```

---

## Self-Review

**Spec coverage.**

| Spec requirement | Task |
|---|---|
| §4 six-digit code, order-independent | 2 (derivation exists from Plan 1 Task 4) |
| §4 user confirms the code matches on the other device | 2, 6 |
| §4 both persist `{deviceId, fingerprint, displayName}` | 2, 6 |
| §4 exactly one paired peer; re-pairing replaces | 6 |
| §4 unpaired devices are ignored | 3 (preserved outside the window) |
| §4 the only message an unpaired peer can elicit is a pairing offer | 3, 6 (restricted handler) |
| §5 discovering an unpaired peer | 5 |
| §11 LAN-only on the pairing path | 4, 5 |
| Plan 1 gap: no pairing bootstrap | closed, Tasks 1–7 |
| Plan 1 gap: `protocol.md` path | closed, Task 7 |
| Plan 1 gap: cross-machine verification | **still open** — needs hardware |

**Placeholder scan.** No `TBD` or `TODO`. Task 5 Step 3 deliberately describes a *requirement* rather than verbatim code, because the file it edits was restructured during Plan 1's review and the exact fan-out shape is not knowable from here — the requirement, the prohibition ("no second `ReceiveAsync`"), and a regression test are all specified. That is the honest form for this one step.

**Type consistency.** `PairOfferPayload(int Version, string DeviceId, string Name, string Fingerprint)` is Plan 1's existing record, used unchanged in Tasks 2 and 6 — note the field is `Name`, not `DisplayName`, and Task 2's test and Task 6's coordinator both use `Name`. `PairedPeer(DeviceId, Fingerprint, DisplayName, PairedAt)` is constructed identically in Tasks 2 and 6. `PairingState` has four members, referenced consistently in Tasks 2 and 6. `PairingWindow.IsOpen` is the only gate consulted in Tasks 3, 5, and 6.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-25-pairing-bootstrap.md`. Two execution options:

**1. Subagent-Driven (recommended)** — a fresh subagent per task, with review between tasks. Tasks 3 and 6 are security-critical and warrant the standard model rather than a cheaper one.

**2. Inline Execution** — execute tasks in this session using executing-plans, batching with checkpoints for review.
