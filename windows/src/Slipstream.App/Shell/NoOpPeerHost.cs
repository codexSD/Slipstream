using Slipstream.App.Services;
using Slipstream.Core.Files;
using Slipstream.Core.Identity;
using Slipstream.Core.Transfer;

namespace Slipstream.App.Shell;

/// <summary>
/// Placeholder <see cref="IPeerHost"/> used to wire up <see cref="ShellWindow"/> until Task 8
/// implements the real <c>PeerHost</c> against this same interface. Never raises
/// <see cref="StateChanged"/>, so the shell simply shows "Not connected" until it is swapped
/// out at the composition root (<see cref="Slipstream_App.App"/>).
/// </summary>
internal sealed class NoOpPeerHost : IPeerHost
{
    public PeerConnectionState State => PeerConnectionState.Idle;
    public string? PeerName => null;
    public string? Band => null;

    public event Action<PeerConnectionState, string?, string?>? StateChanged
    {
        add { }
        remove { }
    }

    public Task StartAsync(CancellationToken ct) => Task.CompletedTask;

    public Task<bool> ReconnectAsync(CancellationToken ct) => Task.FromResult(false);

    public Task<IReadOnlyList<FileEntry>> ListAsync(string path, CancellationToken ct)
        => Task.FromResult<IReadOnlyList<FileEntry>>([]);

    public Task<string> PullAsync(string remotePath, IProgress<TransferProgress>? progress, CancellationToken ct)
        => Task.FromResult(string.Empty);

    public Task StreamAsync(string remotePath, CancellationToken ct) => Task.CompletedTask;

    public Task SendClipboardAsync(string text, CancellationToken ct) => Task.CompletedTask;

    public Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>> confirm, CancellationToken ct)
        => Task.FromResult<PairedPeer?>(null);
}
