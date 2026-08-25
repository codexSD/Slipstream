using Slipstream.Core.Files;
using Slipstream.Core.Identity;
using Slipstream.Core.Transfer;

namespace Slipstream.App.Services;

public enum PeerConnectionState { Idle, Searching, Connected, Degraded, Lost }

public interface IPeerHost
{
    PeerConnectionState State { get; }
    string? PeerName { get; }
    string? Band { get; }

    /// <summary>
    /// The <c>IDiscoveryStrategy.Name</c> (e.g. "cached-endpoint", "gateway-probe",
    /// "multicast", "subnet-sweep") that won the most recent successful discovery, or
    /// null before any discovery has completed. Set alongside <see cref="DiscoveryElapsed"/>
    /// right before the connection state transitions to Connected, so the Device page can
    /// answer "why did it take that long".
    /// </summary>
    string? DiscoveryStrategy { get; }

    /// <summary>How long the most recent successful discovery took, or null before any
    /// discovery has completed.</summary>
    TimeSpan? DiscoveryElapsed { get; }

    event Action<PeerConnectionState, string?, string?>? StateChanged;
    Task StartAsync(CancellationToken ct);
    Task<bool> ReconnectAsync(CancellationToken ct);
    Task<ListResult> ListAsync(string path, CancellationToken ct);
    Task<string> PullAsync(string remotePath, IProgress<TransferProgress>? progress, CancellationToken ct);
    Task StreamAsync(string remotePath, CancellationToken ct);
    Task SendClipboardAsync(string text, CancellationToken ct);
    Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>> confirm, CancellationToken ct);
}
