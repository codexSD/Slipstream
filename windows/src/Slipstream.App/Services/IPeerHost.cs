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
    event Action<PeerConnectionState, string?, string?>? StateChanged;
    Task StartAsync(CancellationToken ct);
    Task<bool> ReconnectAsync(CancellationToken ct);
    Task<IReadOnlyList<FileEntry>> ListAsync(string path, CancellationToken ct);
    Task<string> PullAsync(string remotePath, IProgress<TransferProgress>? progress, CancellationToken ct);
    Task StreamAsync(string remotePath, CancellationToken ct);
    Task SendClipboardAsync(string text, CancellationToken ct);
    Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>> confirm, CancellationToken ct);
}
