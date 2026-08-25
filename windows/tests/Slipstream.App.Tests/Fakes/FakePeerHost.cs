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

    public Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>> confirm, CancellationToken ct)
        => Task.FromResult<PairedPeer?>(null);
}
