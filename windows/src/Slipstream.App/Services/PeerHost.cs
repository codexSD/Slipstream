using System.Net;
using System.Runtime.Versioning;
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Control.Handlers;
using Slipstream.Core.Files;
using Slipstream.Core.Identity;
using Slipstream.Core.Platform;
using Slipstream.Core.Transfer;

namespace Slipstream.App.Services;

/// <summary>
/// Thrown by <see cref="PeerHost.SendRequestAsync"/> when the control connection closes mid
/// round trip. Deliberately NOT a <see cref="ControlProtocolException"/>: that type means the
/// peer explicitly refused a request (e.g. "file no longer exists" — not worth retrying), while
/// this means the connection itself dropped — a connectivity-class failure the reconnect loop
/// is already recovering from, which the retry loops in <see cref="PeerHost"/> DO retry.
/// </summary>
public sealed class ControlConnectionLostException(string message) : Exception(message);

/// <summary>
/// The single owner of one <see cref="SlipstreamPeer"/>'s lifetime and the one control
/// connection it keeps to its paired peer, plus the reconnect loop that keeps that
/// connection alive across the network switches spec §5 treats as routine.
/// </summary>
/// <remarks>
/// The control channel is a single duplex stream: two concurrent request/response pairs
/// would interleave their replies on the wire, so every control round trip — a browse, a
/// pull handshake, a heartbeat — is serialised behind <see cref="_gate"/>, held only for
/// that round trip. <see cref="PullAsync"/> is the one exception worth calling out: it
/// gates the pull.request/pull.ok handshake but NOT the bulk byte transfer that follows,
/// since that transfer runs over its own socket (<see cref="SlipstreamPorts.Bulk"/>) and
/// can legitimately run for many seconds — holding the gate for it would starve the
/// heartbeat loop and any concurrent control call for the whole transfer. Nothing here
/// ever touches a UI thread; that dispatch is the view models' job.
/// </remarks>
[SupportedOSPlatform("windows")]
public sealed class PeerHost : IPeerHost, IAsyncDisposable
{
    private static readonly TimeSpan DiscoveryTimeout = TimeSpan.FromSeconds(8);
    private static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(8);
    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromMilliseconds(750);
    private static readonly TimeSpan HeartbeatReplyTimeout = TimeSpan.FromSeconds(3);
    private static readonly TimeSpan MinReconnectBackoff = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan MaxReconnectBackoff = TimeSpan.FromSeconds(15);
    private const int MaxResumeAttempts = 40;
    private static readonly TimeSpan ResumeRetryDelay = TimeSpan.FromMilliseconds(500);
    private static readonly TimeSpan NetworkChangeQuietPeriod = TimeSpan.FromSeconds(1);

    private readonly SlipstreamPeer _peer;
    private readonly string _downloadDirectory;
    // Spec §8: a bare http:// URL handed to ShellExecute opens the default *browser*, not
    // the default media player — the same class of bug PlaylistLauncher already fixes for
    // the phone→PC "play" path (see SlipstreamSession). PC-initiated StreamAsync routes
    // through the same launcher so both directions resolve to a real media player.
    private readonly PlaylistLauncher _playlistLauncher = new(Path.Combine(Path.GetTempPath(), "slipstream"));
    private readonly SemaphoreSlim _gate = new(1, 1);
    // Serialises ReconnectAsync itself: it can be entered both by a caller driving recovery
    // directly and by the NetworkChanged-driven loop, and interleaving two DropConnectionAsync
    // / ConnectAsync sequences would tear down a connection the other one just brought up.
    private readonly SemaphoreSlim _reconnectGate = new(1, 1);
    private readonly CancellationTokenSource _lifetime = new();

    private ControlConnection? _connection;
    private Task? _peerRunTask;
    private Task? _heartbeatTask;
    private Task? _networkChangeTask;
    private bool _started;
    private bool _disposed;
    private int _handlingNetworkChange;
    // Incremented on every NetworkChanged raise (see OnNetworkChanged). Lets ReconnectAsync
    // detect that the network moved again while an attempt was mid-flight, so a connection
    // that finishes against the network the attempt STARTED against isn't mistaken for a
    // successful reconnect to the network that is current NOW — see ReconnectAsync's remarks.
    private int _networkGeneration;

    public PeerHost(SlipstreamPeer peer, string downloadDirectory)
    {
        _peer = peer;
        _downloadDirectory = downloadDirectory;
    }

    public PeerConnectionState State { get; private set; } = PeerConnectionState.Idle;

    public string? PeerName { get; private set; }

    // Core does not currently expose link quality/band information anywhere (no Wi-Fi
    // metrics on SlipstreamPeer, ControlConnection, or DiscoveredPeer), so Degraded is
    // unreachable for now — this never fabricates a value Core has not measured.
    public string? Band { get; private set; }

    public string? DiscoveryStrategy { get; private set; }

    public TimeSpan? DiscoveryElapsed { get; private set; }

    public bool IsDiscoveryPaused { get; private set; }

    public void PauseDiscovery() => IsDiscoveryPaused = true;

    public void ResumeDiscovery() => IsDiscoveryPaused = false;

    public event Action<PeerConnectionState, string?, string?>? StateChanged;

    public async Task StartAsync(CancellationToken ct)
    {
        if (_started) return;
        _started = true;

        _peer.NetworkChanged += OnNetworkChanged;
        _peerRunTask = RunPeerAsync();

        // Give the listener/discovery loops a moment to bind before we start probing.
        await Task.Delay(400, ct);

        if (IsDiscoveryPaused) return;

        await ConnectAsync(ct);
    }

    /// <remarks>
    /// Guards against a race where a caller-driven reconnect is mid-flight when
    /// <see cref="OnNetworkChanged"/> fires (setting Lost, then blocking on
    /// <see cref="_reconnectGate"/>): if the in-flight attempt, started against the OLD
    /// network, completed and reported Connected AFTER the network actually changed, the
    /// NetworkChanged-driven reconnect that follows would otherwise see "already Connected"
    /// via the fast-path check and skip re-discovering on the NEW network entirely. The
    /// generation counter captured at the start of each attempt is compared against the
    /// current one before accepting success; a mismatch means the network moved again while
    /// this attempt was running, so the connection it just brought up is stale and the loop
    /// retries against whatever network is current now instead of reporting a false success.
    /// </remarks>
    public async Task<bool> ReconnectAsync(CancellationToken ct)
    {
        if (IsDiscoveryPaused) return false;

        while (true)
        {
            var generation = Volatile.Read(ref _networkGeneration);

            await _reconnectGate.WaitAsync(ct);
            try
            {
                // Already reconnected by whoever held the gate before us (e.g. the
                // NetworkChanged loop winning a race against a caller-driven reconnect) —
                // nothing left to do, and re-entering would tear down the fresh connection.
                // Only trusted if the network hasn't moved again since we captured `generation`.
                if (State == PeerConnectionState.Connected && generation == Volatile.Read(ref _networkGeneration))
                    return true;

                using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, _lifetime.Token);
                await DropConnectionAsync();

                try
                {
                    await ConnectAsync(linked.Token);
                }
                catch (Exception) when (!ct.IsCancellationRequested)
                {
                    SetState(PeerConnectionState.Lost);
                    return false;
                }

                // The network changed again while this very attempt was connecting: the
                // connection just established may be against a network that is no longer
                // current. Loop and retry rather than reporting a stale success.
                if (generation != Volatile.Read(ref _networkGeneration))
                    continue;

                return State == PeerConnectionState.Connected;
            }
            finally
            {
                _reconnectGate.Release();
            }
        }
    }

    public async Task<ListResult> ListAsync(string path, CancellationToken ct)
    {
        var reply = await SendRequestAsync("list", new ListRequest(path, null), ct);

        if (reply.Type != "list.ok")
            throw new ControlProtocolException(reply.PayloadAs<ErrorResponse>()?.Message ?? "The peer refused to list that folder.");

        var response = reply.PayloadAs<ListResponse>()
            ?? throw new ControlProtocolException("The peer sent a malformed listing.");

        return new ListResult(response.Path, response.Entries, response.Truncated);
    }

    public async Task<string> PullAsync(string remotePath, IProgress<TransferProgress>? progress, CancellationToken ct)
    {
        // Only the pull.request/pull.ok round trip touches the shared control wire, so
        // only that round trip is serialised behind _gate (via SendRequestAsync below).
        // The bulk byte transfer runs over its own socket on SlipstreamPorts.Bulk and
        // must NOT hold the gate — otherwise a multi-second transfer would starve the
        // heartbeat loop's ping, whose 3s timeout would flip State to Lost and would
        // also block unrelated calls like ListAsync for the whole transfer duration.
        //
        // The handshake itself races the network: a change (spec §5) can drop the control
        // connection before or during this very round trip, so it's retried against
        // whatever connection the reconnect loop (heartbeat- or NetworkChanged-driven)
        // brings back, rather than failing on the first attempt.
        var (transferId, token, streams, part, endpoint) = await RequestTransferWithRetryAsync(remotePath, ct);

        await using (part)
        {
            var bulk = new BulkClient();
            try
            {
                await bulk.DownloadAsync(endpoint, transferId, token, part, streams, progress, ct);
            }
            catch (Exception) when (!ct.IsCancellationRequested)
            {
                // The control connection (and possibly the bulk one) died mid-transfer —
                // a dropped Wi-Fi link, a network switch (spec §5), or anything else the
                // heartbeat loop or NetworkChanged handler is already reacting to by
                // tearing down and reconnecting. Wait for that reconnect loop to bring the
                // control connection back, then mint a fresh transfer id/token over it
                // (gated) and resume the byte transfer (ungated) from where the chunk
                // bitmap says it stopped. Retried, not a single attempt, because the
                // reconnect itself can take several backoff cycles.
                await ResumeAfterDisconnectAsync(bulk, remotePath, part, progress, ct);
            }

            if (!await part.CompleteAsync(ct))
                throw new ControlProtocolException("The transfer finished with chunks still missing.");

            // The bulk transfer runs on its own socket, so it can finish successfully even
            // while a network change (spec §5) is still tearing down and re-establishing
            // the control connection in the background. Let that settle before reporting
            // the pull done, so a caller checking State right after PullAsync returns sees
            // the real, final connectivity rather than a mid-reconnect snapshot.
            await WaitForSettledStateAsync(ct);

            return part.DestinationPath;
        }
    }

    /// <summary>Waits for any in-flight reconnect (heartbeat- or NetworkChanged-driven) to
    /// reach <see cref="PeerConnectionState.Connected"/>, bounded so a pull whose transfer
    /// already succeeded never hangs on a control channel that genuinely never comes back.</summary>
    private async Task WaitForSettledStateAsync(CancellationToken ct)
    {
        if (State == PeerConnectionState.Connected && Volatile.Read(ref _handlingNetworkChange) == 0) return;

        using var bound = CancellationTokenSource.CreateLinkedTokenSource(ct);
        bound.CancelAfter(DiscoveryTimeout + ConnectTimeout + MaxReconnectBackoff);

        try
        {
            await WaitForConnectionAsync(bound.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            // Gave up waiting for the control channel to settle; the transfer itself
            // already succeeded, so report that result rather than failing a completed pull.
        }
    }

    private async Task<(Guid TransferId, Guid Token, int Streams, PartFile Part, IPEndPoint Endpoint)> RequestTransferWithRetryAsync(
        string remotePath, CancellationToken ct)
    {
        Exception? last = null;

        for (var attempt = 0; attempt < MaxResumeAttempts; attempt++)
        {
            ct.ThrowIfCancellationRequested();

            try
            {
                await WaitForConnectionAsync(ct);
                return await RequestTransferAsync(remotePath, ct);
            }
            // Only connectivity-class failures (timeouts, socket errors, a dropped
            // connection) are worth retrying here — those are exactly what the reconnect
            // loop (heartbeat- or NetworkChanged-driven) is already recovering from. A
            // ControlProtocolException is the peer explicitly refusing the request (e.g.
            // "file no longer exists") — retrying that 40 times just delays surfacing a
            // real, non-transient failure to the user by 20 seconds.
            catch (Exception ex) when (ex is not ControlProtocolException && !ct.IsCancellationRequested)
            {
                last = ex;
                await Task.Delay(ResumeRetryDelay, ct);
            }
        }

        throw last ?? new ControlProtocolException("Could not start the transfer after the network changed.");
    }

    private async Task<(Guid TransferId, Guid Token, int Streams, PartFile Part, IPEndPoint Endpoint)> RequestTransferAsync(
        string remotePath, CancellationToken ct)
    {
        var reply = await SendRequestAsync("pull.request", new PullRequest(remotePath), ct);

        if (reply.Type != "pull.ok")
            throw new ControlProtocolException(reply.PayloadAs<ErrorResponse>()?.Message ?? "The peer refused the transfer.");

        var response = reply.PayloadAs<PullResponse>()
            ?? throw new ControlProtocolException("The peer sent a malformed transfer response.");

        // Captured in the same breath as the successful reply, not re-read later: by the
        // time the caller gets around to using it, a network change (spec §5) may already
        // have dropped this very connection, and re-reading _connection at that point could
        // race an unguarded null. If this snapshot IS already stale, the bulk transfer that
        // uses it will simply fail into PullAsync's own resume-after-disconnect retry.
        var connection = _connection ?? throw new InvalidOperationException("Not connected.");
        var endpoint = new IPEndPoint(connection.RemoteEndPoint.Address, SlipstreamPorts.Bulk);

        Directory.CreateDirectory(_downloadDirectory);

        var transferId = Guid.Parse(response.TransferId);
        var token = Guid.Parse(response.Token);
        // response.Name comes off the wire from the peer: strip any directory-traversal
        // components before it becomes a path component, so a malicious/corrupted peer
        // response can't write outside the download directory (defense-in-depth given the
        // paired-only trust model).
        var destination = Path.Combine(_downloadDirectory, Path.GetFileName(response.Name));
        var streams = Math.Min(_peer.StreamCount, response.Streams);

        var part = PartFile.OpenOrCreate(destination, transferId, response.Size, response.ChunkSize);

        return (transferId, token, streams, part, endpoint);
    }

    private async Task<(Guid TransferId, Guid Token, int Streams, IPEndPoint Endpoint)> RequestFreshTokenAsync(string remotePath, CancellationToken ct)
    {
        var reply = await SendRequestAsync("pull.request", new PullRequest(remotePath), ct);

        if (reply.Type != "pull.ok")
            throw new ControlProtocolException(reply.PayloadAs<ErrorResponse>()?.Message ?? "The peer refused the transfer.");

        var response = reply.PayloadAs<PullResponse>()
            ?? throw new ControlProtocolException("The peer sent a malformed transfer response.");

        // Captured in the same breath as the successful reply (same gated round trip as
        // RequestTransferAsync), not from a separately-captured WaitForConnectionAsync
        // result: _connection can be swapped out by a reconnect between those two points,
        // which would otherwise send the resumed bulk transfer to a stale/mismatched
        // endpoint while the fresh token was minted against a different connection.
        var connection = _connection ?? throw new InvalidOperationException("Not connected.");
        var endpoint = new IPEndPoint(connection.RemoteEndPoint.Address, SlipstreamPorts.Bulk);

        return (Guid.Parse(response.TransferId), Guid.Parse(response.Token), Math.Min(_peer.StreamCount, response.Streams), endpoint);
    }

    private async Task ResumeAfterDisconnectAsync(
        BulkClient bulk, string remotePath, PartFile part, IProgress<TransferProgress>? progress, CancellationToken ct)
    {
        Exception? last = null;

        for (var attempt = 0; attempt < MaxResumeAttempts; attempt++)
        {
            ct.ThrowIfCancellationRequested();

            try
            {
                await WaitForConnectionAsync(ct);
                var (retryTransferId, retryToken, retryStreams, resumeEndpoint) = await RequestFreshTokenAsync(remotePath, ct);
                await bulk.DownloadAsync(resumeEndpoint, retryTransferId, retryToken, part, retryStreams, progress, ct);
                return;
            }
            // Only connectivity-class failures (timeouts, socket errors, a dropped
            // connection) are worth retrying here — those are exactly what the reconnect
            // loop (heartbeat- or NetworkChanged-driven) is already recovering from. A
            // ControlProtocolException is the peer explicitly refusing the request (e.g.
            // "file no longer exists") — retrying that 40 times just delays surfacing a
            // real, non-transient failure to the user by 20 seconds.
            catch (Exception ex) when (ex is not ControlProtocolException && !ct.IsCancellationRequested)
            {
                last = ex;
                await Task.Delay(ResumeRetryDelay, ct);
            }
        }

        throw last ?? new ControlProtocolException("Could not resume the transfer after the network changed.");
    }

    /// <summary>Waits until the reconnect loop (heartbeat- or NetworkChanged-driven) has a
    /// live control connection again, rather than failing the moment one attempt races
    /// ahead of the other side reconnecting.</summary>
    private async Task<ControlConnection> WaitForConnectionAsync(CancellationToken ct)
    {
        while (true)
        {
            ct.ThrowIfCancellationRequested();

            if (_connection is { } connection && State == PeerConnectionState.Connected)
                return connection;

            await Task.Delay(100, ct);
        }
    }

    private void OnNetworkChanged()
    {
        // Bump on every raise (not just the coalesced ones below) — this is what lets
        // ReconnectAsync detect "the network moved again while my attempt was in flight",
        // even for a raise that gets coalesced away by _handlingNetworkChange.
        Interlocked.Increment(ref _networkGeneration);

        // Coalesce bursts of NetworkChange.NetworkAddressChanged (the OS can fire it more
        // than once for a single physical switch) into a single reconnect loop.
        if (Interlocked.CompareExchange(ref _handlingNetworkChange, 1, 0) != 0) return;

        _networkChangeTask = HandleNetworkChangeAsync(_lifetime.Token);
    }

    private async Task HandleNetworkChangeAsync(CancellationToken ct)
    {
        try
        {
            // Spec §5: a network switch is routine, never an error state.
            SetState(PeerConnectionState.Lost);

            var backoff = MinReconnectBackoff;
            while (!ct.IsCancellationRequested)
            {
                if (await ReconnectAsync(ct)) break;

                try
                {
                    await Task.Delay(backoff, ct);
                }
                catch (OperationCanceledException)
                {
                    return;
                }

                backoff = TimeSpan.FromSeconds(Math.Min(backoff.TotalSeconds * 2, MaxReconnectBackoff.TotalSeconds));
            }

            // Absorb a burst of follow-up NetworkChanged notifications the OS can still
            // deliver for what is really one physical switch (see OnNetworkChanged) before
            // letting a fresh event start a whole new teardown/reconnect cycle.
            try { await Task.Delay(NetworkChangeQuietPeriod, ct); } catch (OperationCanceledException) { }
        }
        finally
        {
            Volatile.Write(ref _handlingNetworkChange, 0);
        }
    }

    public async Task StreamAsync(string remotePath, CancellationToken ct)
    {
        var reply = await SendRequestAsync("stream.request", new StatRequest(remotePath), ct);

        if (reply.Type != "stream.ok")
            throw new ControlProtocolException(reply.PayloadAs<ErrorResponse>()?.Message ?? "The peer refused to stream that file.");

        var response = reply.PayloadAs<PlayMessage>()
            ?? throw new ControlProtocolException("The peer sent a malformed stream response.");

        // Fire and forget — spec §8: no remote control, no position sync. PlaylistLauncher
        // (not a bare ShellExecute on the URL) owns handing playback off to a real media
        // player from here.
        _playlistLauncher.Launch(new PlayRequest(response.Url, response.Title, response.Mime));
    }

    public string? GetThumbnailUrl(string thumbnailToken)
    {
        if (_connection is not { } connection) return null;

        // Same "/thumb/{token}" route MediaServer already serves (see MediaServer's raw,
        // non-vaulted thumbnail lookup) against the same fixed media port every server
        // binds to (SlipstreamPorts.Media) — the same convention StreamAsync's response.Url
        // resolves against, just built locally since the token is already in hand from the
        // listing, with no control round trip needed.
        return $"http://{connection.RemoteEndPoint.Address}:{SlipstreamPorts.Media}/thumb/{thumbnailToken}";
    }

    public async Task SendClipboardAsync(string text, CancellationToken ct)
    {
        var reply = await SendRequestAsync("clipboard", new ClipboardMessage(text), ct);

        if (reply.Type != "clipboard.ok")
            throw new ControlProtocolException(reply.PayloadAs<ErrorResponse>()?.Message ?? "The peer refused the clipboard text.");
    }

    public Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>> confirm, CancellationToken ct) =>
        _peer.PairAsync(confirm, ConnectTimeout, ct);

    private async Task ConnectAsync(CancellationToken ct)
    {
        SetState(PeerConnectionState.Searching);

        var found = await _peer.FindPeerAsync(DiscoveryTimeout, ct);
        if (found is null)
        {
            SetState(PeerConnectionState.Lost);
            throw new InvalidOperationException("Could not find the paired peer on this network.");
        }

        var connection = await _peer.Client.ConnectAsync(found.Peer.Endpoint, ConnectTimeout, ct);
        if (connection is null)
        {
            SetState(PeerConnectionState.Lost);
            throw new InvalidOperationException("The paired peer refused the connection.");
        }

        DiscoveryStrategy = found.StrategyName;
        DiscoveryElapsed = found.Elapsed;

        _connection = connection;
        PeerName = _peer.Peers.Current?.DisplayName;
        SetState(PeerConnectionState.Connected);

        _heartbeatTask = HeartbeatLoopAsync(_lifetime.Token);
    }

    private async Task HeartbeatLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(HeartbeatInterval, ct);

                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeout.CancelAfter(HeartbeatReplyTimeout);

                await SendRequestAsync("ping", null, timeout.Token);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception)
            {
                // The peer is gone — a dropped Wi-Fi link, a sleeping laptop, a closed
                // app. Spec §5: this is routine, not an error state. ReconnectAsync is
                // the caller's recovery path; this loop's job ends here.
                SetState(PeerConnectionState.Lost);
                return;
            }
        }
    }

    private async Task<ControlMessage> SendRequestAsync(string type, object? payload, CancellationToken ct)
    {
        var connection = _connection ?? throw new InvalidOperationException("Not connected.");
        var id = Guid.NewGuid().ToString("N")[..8];

        await _gate.WaitAsync(ct);
        try
        {
            await connection.SendAsync(ControlMessage.Request(type, id, payload), ct);

            while (true)
            {
                var message = await connection.ReceiveAsync(ct)
                    ?? throw new ControlConnectionLostException("The peer closed the control connection.");

                if (message.Id == id) return message;
                // Anything else interleaved on the wire is not this request's reply; keep reading.
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task RunPeerAsync()
    {
        try
        {
            await _peer.StartAsync(_lifetime.Token);
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown via DisposeAsync.
        }
    }

    private async Task DropConnectionAsync()
    {
        var connection = _connection;
        _connection = null;

        if (connection is not null) await connection.DisposeAsync();

        var heartbeat = _heartbeatTask;
        _heartbeatTask = null;
        if (heartbeat is not null)
        {
            try { await heartbeat; } catch { /* already logged via state transition */ }
        }
    }

    private void SetState(PeerConnectionState state)
    {
        State = state;
        StateChanged?.Invoke(state, PeerName, Band);
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;

        _peer.NetworkChanged -= OnNetworkChanged;

        await _lifetime.CancelAsync();
        await DropConnectionAsync();

        if (_networkChangeTask is not null)
        {
            try { await _networkChangeTask; } catch { /* already logged via state transition */ }
        }

        if (_peerRunTask is not null)
        {
            try { await _peerRunTask; } catch { /* swallowed above */ }
        }

        await _peer.DisposeAsync();
        _lifetime.Dispose();
        _gate.Dispose();
        _reconnectGate.Dispose();
    }
}
