using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Control;

public sealed record HelloPayload(int Version, string DeviceId, string Name, string Fingerprint);

public sealed record PairOfferPayload(int Version, string DeviceId, string Name, string Fingerprint);

/// <summary>
/// Inbound half of the control channel. Binds to a specific local address (spec §11
/// layer 1) and drops any connection whose client certificate is not the paired peer.
/// </summary>
public sealed class ControlServer : IAsyncDisposable
{
    private readonly DeviceIdentity _identity;
    private readonly PairedPeerStore _peers;
    private readonly TcpListener _listener;
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

    public IPEndPoint ListenEndPoint => (IPEndPoint)_listener.LocalEndpoint;

    /// <summary>Raised once per accepted, fingerprint-verified connection.</summary>
    public event Func<ControlConnection, CancellationToken, Task>? PeerConnected;

    /// <summary>
    /// Raised only for connections accepted through the pairing path — an unpaired peer
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

            _ = HandleAsync(client, cancellationToken);
        }
    }

    private async Task HandleAsync(TcpClient client, CancellationToken cancellationToken)
    {
        try
        {
            client.NoDelay = true;
            var remote = (IPEndPoint)client.Client.RemoteEndPoint!;

            // Spec §11 layer 2, applied to inbound connections too.
            if (!LanGuard.IsLocal(remote.Address))
            {
                client.Dispose();
                return;
            }

            var stream = await PinnedTls.AuthenticateAsServerAsync(
                client.GetStream(), _identity, cancellationToken);

            var fingerprint = PinnedTls.FingerprintOf(stream);

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
        }
        catch (Exception)
        {
            // A failed inbound connection is routine — never take the listener down.
        }
        finally
        {
            client.Dispose();
        }
    }

    public ValueTask DisposeAsync()
    {
        _listener.Stop();
        return ValueTask.CompletedTask;
    }
}
