using Slipstream.App.Services;
using Slipstream.Core.Files;
using Slipstream.Core.Identity;
using Slipstream.Core.Transfer;

namespace Slipstream.App.Tests.Fakes;

/// <summary>Test double for <see cref="IPeerHost"/>. Tests drive it with <see cref="RaiseState"/>
/// rather than exercising real networking.</summary>
public sealed class FakePeerHost : IPeerHost
{
    public PeerConnectionState State { get; private set; } = PeerConnectionState.Idle;
    public string? PeerName { get; private set; }
    public string? Band { get; private set; }
    public string? DiscoveryStrategy { get; private set; }
    public TimeSpan? DiscoveryElapsed { get; private set; }

    public event Action<PeerConnectionState, string?, string?>? StateChanged;

    /// <summary>Set true once <see cref="PairAsync"/> has run its confirm callback to
    /// completion and the callback returned true. Tests that never call
    /// <see cref="PairAsync"/> at all (e.g. a decline that happens before pairing starts)
    /// correctly leave this false.</summary>
    public bool Paired { get; private set; }

    /// <summary>The code <see cref="PairAsync"/> hands to the confirm callback. Tests set
    /// this before calling <see cref="PairAsync"/> to simulate the code that arrived from
    /// the peer (derivation itself is Slipstream.Core's concern, not this fake's).</summary>
    public string PairingCode { get; set; } = "000000";

    public void RaiseState(PeerConnectionState state, string? peerName, string? band = null)
    {
        State = state;
        PeerName = peerName;
        Band = band;
        StateChanged?.Invoke(state, peerName, band);
    }

    /// <summary>Simulates a completed discovery (e.g. immediately before a Connected state).</summary>
    public void RaiseDiscovery(string strategy, TimeSpan elapsed)
    {
        DiscoveryStrategy = strategy;
        DiscoveryElapsed = elapsed;
    }

    public Task StartAsync(CancellationToken ct) => Task.CompletedTask;

    public Task<bool> ReconnectAsync(CancellationToken ct) => Task.FromResult(true);

    public Task<IReadOnlyList<FileEntry>> ListAsync(string path, CancellationToken ct)
        => Task.FromResult<IReadOnlyList<FileEntry>>([]);

    public Task<string> PullAsync(string remotePath, IProgress<TransferProgress>? progress, CancellationToken ct)
        => Task.FromResult(string.Empty);

    public Task StreamAsync(string remotePath, CancellationToken ct) => Task.CompletedTask;

    public Task SendClipboardAsync(string text, CancellationToken ct) => Task.CompletedTask;

    public async Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>> confirm, CancellationToken ct)
    {
        var confirmed = await confirm(PairingCode, ct);
        if (!confirmed)
            return null;

        Paired = true;
        return new PairedPeer("fake-device-id", "fake-fingerprint", PeerName ?? "Fake Peer", DateTimeOffset.UtcNow);
    }
}
