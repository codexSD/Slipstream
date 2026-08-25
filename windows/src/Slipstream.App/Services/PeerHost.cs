using System.Diagnostics;
using System.Net;
using System.Runtime.Versioning;
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Control.Handlers;
using Slipstream.Core.Files;
using Slipstream.Core.Identity;
using Slipstream.Core.Transfer;

namespace Slipstream.App.Services;

/// <summary>
/// The single owner of one <see cref="SlipstreamPeer"/>'s lifetime and the one control
/// connection it keeps to its paired peer, plus the reconnect loop that keeps that
/// connection alive across the network switches spec §5 treats as routine.
/// </summary>
/// <remarks>
/// The control channel is a single duplex stream: two concurrent request/response pairs
/// would interleave their replies on the wire, so every request — a browse, a pull
/// handshake, a heartbeat — is serialised behind <see cref="_gate"/>, held for the full
/// round trip. Nothing here ever touches a UI thread; that dispatch is the view models' job.
/// </remarks>
[SupportedOSPlatform("windows")]
public sealed class PeerHost : IPeerHost, IAsyncDisposable
{
    private static readonly TimeSpan DiscoveryTimeout = TimeSpan.FromSeconds(8);
    private static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(8);
    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromMilliseconds(750);
    private static readonly TimeSpan HeartbeatReplyTimeout = TimeSpan.FromSeconds(3);

    private readonly SlipstreamPeer _peer;
    private readonly string _downloadDirectory;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly CancellationTokenSource _lifetime = new();

    private ControlConnection? _connection;
    private Task? _peerRunTask;
    private Task? _heartbeatTask;
    private bool _started;
    private bool _disposed;

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

    public event Action<PeerConnectionState, string?, string?>? StateChanged;

    public async Task StartAsync(CancellationToken ct)
    {
        if (_started) return;
        _started = true;

        _peerRunTask = RunPeerAsync();

        // Give the listener/discovery loops a moment to bind before we start probing.
        await Task.Delay(400, ct);

        await ConnectAsync(ct);
    }

    public async Task<bool> ReconnectAsync(CancellationToken ct)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, _lifetime.Token);
        await DropConnectionAsync();

        try
        {
            await ConnectAsync(linked.Token);
            return State == PeerConnectionState.Connected;
        }
        catch (Exception) when (!ct.IsCancellationRequested)
        {
            SetState(PeerConnectionState.Lost);
            return false;
        }
    }

    public async Task<IReadOnlyList<FileEntry>> ListAsync(string path, CancellationToken ct)
    {
        var reply = await SendRequestAsync("list", new ListRequest(path, null), ct);

        if (reply.Type != "list.ok")
            throw new ControlProtocolException(reply.PayloadAs<ErrorResponse>()?.Message ?? "The peer refused to list that folder.");

        var response = reply.PayloadAs<ListResponse>()
            ?? throw new ControlProtocolException("The peer sent a malformed listing.");

        return response.Entries;
    }

    public async Task<string> PullAsync(string remotePath, IProgress<TransferProgress>? progress, CancellationToken ct)
    {
        var connection = _connection ?? throw new InvalidOperationException("Not connected.");
        var endpoint = new IPEndPoint(connection.RemoteEndPoint.Address, SlipstreamPorts.Bulk);

        await _gate.WaitAsync(ct);
        try
        {
            return await _peer.Engine.PullAsync(connection, endpoint, remotePath, progress, ct);
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task StreamAsync(string remotePath, CancellationToken ct)
    {
        var reply = await SendRequestAsync("stream.request", new StatRequest(remotePath), ct);

        if (reply.Type != "stream.ok")
            throw new ControlProtocolException(reply.PayloadAs<ErrorResponse>()?.Message ?? "The peer refused to stream that file.");

        var response = reply.PayloadAs<PlayMessage>()
            ?? throw new ControlProtocolException("The peer sent a malformed stream response.");

        // Fire and forget — spec §8: no remote control, no position sync. The OS default
        // handler for the URL owns playback from here.
        Process.Start(new ProcessStartInfo(response.Url) { UseShellExecute = true })?.Dispose();
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
                    ?? throw new ControlProtocolException("The peer closed the control connection.");

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

        await _lifetime.CancelAsync();
        await DropConnectionAsync();

        if (_peerRunTask is not null)
        {
            try { await _peerRunTask; } catch { /* swallowed above */ }
        }

        await _peer.DisposeAsync();
        _lifetime.Dispose();
        _gate.Dispose();
    }
}
