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

    /// <summary>Whether discovery/reconnect attempts are currently suppressed — see
    /// <see cref="PauseDiscovery"/>.</summary>
    bool IsDiscoveryPaused { get; }

    /// <summary>Stops this host from searching for or reconnecting to the paired peer.
    /// <see cref="StartAsync"/> and <see cref="ReconnectAsync"/> become no-ops (returning
    /// without attempting discovery) until <see cref="ResumeDiscovery"/> is called. Wired to
    /// the tray icon's "Pause discovery" menu item (Task 15) so a user who wants Slipstream
    /// quiet on a given network can stop it probing without quitting the app.</summary>
    void PauseDiscovery();

    /// <summary>Re-allows discovery/reconnect after <see cref="PauseDiscovery"/>. Does not by
    /// itself trigger a new attempt — the next <see cref="ReconnectAsync"/> call (e.g. from
    /// the shell's own retry loop) will proceed normally.</summary>
    void ResumeDiscovery();

    event Action<PeerConnectionState, string?, string?>? StateChanged;
    Task StartAsync(CancellationToken ct);
    Task<bool> ReconnectAsync(CancellationToken ct);
    Task<ListResult> ListAsync(string path, CancellationToken ct);
    Task<string> PullAsync(string remotePath, IProgress<TransferProgress>? progress, CancellationToken ct);
    Task StreamAsync(string remotePath, CancellationToken ct);
    Task SendClipboardAsync(string text, CancellationToken ct);

    /// <summary>Resolves a <see cref="Files.FileEntry.ThumbnailToken"/> into a loadable HTTP
    /// URL against the connected peer's media server (spec §9: thumbnails are served the
    /// same way as media streams — a token URL against the peer's advertised address), or
    /// null when not currently connected to a peer. No network round trip: the token is
    /// already known from the listing that produced it, so this is pure URL construction.</summary>
    string? GetThumbnailUrl(string thumbnailToken);
    Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>> confirm, CancellationToken ct);

    /// <summary>Whether this device currently has a paired peer.</summary>
    bool IsPaired { get; }

    /// <summary>Forgets the paired peer and drops any connection to it. The counterpart to
    /// <see cref="PairAsync"/>, and the only way out of a pairing that has gone stale — a
    /// stored fingerprint that no longer matches the peer's certificate causes it to be
    /// rejected during discovery, which is indistinguishable from the peer not being there.</summary>
    void Unpair();
}
