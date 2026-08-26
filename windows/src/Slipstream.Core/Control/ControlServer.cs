using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Control;

public sealed record HelloPayload(int Version, string DeviceId, string Name, string Fingerprint);

public sealed record PairOfferPayload(int Version, string DeviceId, string Name, string Fingerprint);

/// <summary>
/// Inbound half of the control channel. Binds to a specific local address (spec Â§11
/// layer 1) and drops any connection whose client certificate is not the paired peer.
/// </summary>
public sealed class ControlServer : IAsyncDisposable
{
    private readonly DeviceIdentity _identity;
    private readonly PairedPeerStore _peers;
    private readonly TcpListener _listener;
    private readonly PairingWindow? _pairingWindow;
    private readonly ConcurrentDictionary<ControlConnection, byte> _connections = new();
    // Every accepted socket, registered the instant AcceptTcpClientAsync returns — before
    // the TLS handshake and the trust check that _connections waits for. A remote peer
    // finishes its side of the handshake and considers the link up while this server's
    // continuation may not have run yet, so _connections is NOT a complete picture of the
    // live sockets: anything acting on "every live connection" (shutdown, or a forced
    // sever) must work from this set or it will silently skip a connection the peer is
    // already using. See CloseActiveConnections.
    private readonly HashSet<TcpClient> _liveClients = [];
    private readonly object _liveClientsLock = new();

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

    public IPEndPoint ListenEndPoint => (IPEndPoint)_listener.LocalEndpoint;

    /// <summary>
    /// Every trusted connection currently accepted (i.e. <see cref="PeerConnected"/> is live
    /// for it). Observation only, and deliberately incomplete: a connection appears here only
    /// once its TLS handshake and fingerprint check have finished on this side, which can be
    /// *after* the remote peer's handshake completed and it began sending. To act on every
    /// live socket — severing or shutting down — use <see cref="CloseActiveConnections"/>.
    /// </summary>
    public IReadOnlyCollection<ControlConnection> Connections => _connections.Keys.ToList();

    /// <summary>Raised once per accepted, fingerprint-verified connection.</summary>
    public event Func<ControlConnection, CancellationToken, Task>? PeerConnected;

    /// <summary>
    /// Raised only for connections accepted through the pairing path â€” an unpaired peer
    /// during an open pairing window. These connections may speak `pair.*` and nothing
    /// else; they must never be handed to the browse/transfer session.
    /// </summary>
    public event Func<ControlConnection, CancellationToken, Task>? PairingConnected;

    public async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (SocketException)
            {
                continue;
            }

            // Registered here, synchronously in the accept loop, rather than inside
            // HandleAsync: the socket must be visible to CloseActiveConnections from the
            // moment it exists, since the peer can complete its half of the handshake and
            // start using the connection before HandleAsync's first continuation runs.
            lock (_liveClientsLock)
                _liveClients.Add(client);

            _ = HandleAsync(client, cancellationToken);
        }
    }

    /// <summary>
    /// Forcibly closes every socket this server has accepted, including ones still mid
    /// handshake and therefore not yet in <see cref="Connections"/>. Used on shutdown, and
    /// by tests that simulate a dropped link (spec §5): the peer process stays up, only the
    /// TCP/TLS streams die. Closed with a reset rather than a graceful FIN so a peer blocked
    /// in a read fails immediately instead of waiting on an orderly shutdown.
    /// </summary>
    public void CloseActiveConnections()
    {
        TcpClient[] clients;
        lock (_liveClientsLock)
            clients = [.. _liveClients];

        foreach (var client in clients)
        {
            try { client.Client.Close(0); }
            catch (Exception) { /* best effort: already gone is already severed */ }
        }
    }

    private async Task HandleAsync(TcpClient client, CancellationToken cancellationToken)
    {
        try
        {
            client.NoDelay = true;
            var remote = (IPEndPoint)client.Client.RemoteEndPoint!;

            // Spec Â§11 layer 2, applied to inbound connections too.
            if (!LanGuard.IsLocal(remote.Address))
            {
                client.Dispose();
                return;
            }

            var stream = await PinnedTls.AuthenticateAsServerAsync(
                client.GetStream(), _identity, cancellationToken);

            var fingerprint = PinnedTls.FingerprintOf(stream);

            var trusted = _peers.Trusts(fingerprint);

            // Unpaired devices get nothing â€” unless the user has deliberately opened a
            // pairing window, in which case they reach the restricted pairing handler only.
            if (!trusted && _pairingWindow?.IsOpen != true)
            {
                await stream.DisposeAsync();
                client.Dispose();
                return;
            }

            await using var connection = new ControlConnection(stream, fingerprint, remote);

            if (trusted) _connections.TryAdd(connection, 0);
            try
            {
                var handler = trusted ? PeerConnected : PairingConnected;
                if (handler is not null) await handler(connection, cancellationToken);
            }
            finally
            {
                _connections.TryRemove(connection, out _);
            }
        }
        catch (Exception)
        {
            // A failed inbound connection is routine â€” never take the listener down.
        }
        finally
        {
            lock (_liveClientsLock)
                _liveClients.Remove(client);

            client.Dispose();
        }
    }

    public ValueTask DisposeAsync()
    {
        _listener.Stop();
        // Stopping the listener only refuses new connections; without this, every socket
        // already accepted outlives the server object and keeps running until the caller's
        // cancellation token happens to fire.
        CloseActiveConnections();
        return ValueTask.CompletedTask;
    }
}
