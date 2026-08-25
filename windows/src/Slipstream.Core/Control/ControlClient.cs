using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Control;

/// <summary>
/// Outbound half of the control channel, and the source of the real PeerProbe that
/// discovery strategies consume.
/// </summary>
public sealed class ControlClient(DeviceIdentity identity, PairedPeerStore peers)
{
    /// <summary>
    /// A probe that answers "is our paired peer here?" and never throws for an
    /// unreachable host — during a 254-way sweep, unreachable is the normal answer.
    /// </summary>
    public PeerProbe CreateProbe(TimeSpan timeout) => async (endpoint, cancellationToken) =>
    {
        var peer = peers.Current;
        if (peer is null) return null;
        if (!LanGuard.IsLocal(endpoint.Address)) return null;

        try
        {
            await using var connection = await ConnectAsync(endpoint, timeout, cancellationToken);
            return connection is null ? null : new DiscoveredPeer(peer, endpoint);
        }
        catch (Exception) when (!cancellationToken.IsCancellationRequested)
        {
            return null;
        }
    };

    /// <summary>
    /// Connects and completes a fingerprint-pinned handshake. Returns null when the
    /// peer is reachable but is not our paired peer.
    /// </summary>
    public async Task<ControlConnection?> ConnectAsync(
        IPEndPoint endpoint,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        LanGuard.EnsureLocal(endpoint.Address);

        if (!peers.IsPaired) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        linked.CancelAfter(timeout);

        var tcp = new TcpClient();
        try
        {
            tcp.NoDelay = true;
            await tcp.ConnectAsync(endpoint, linked.Token);

            var stream = await PinnedTls.AuthenticateAsClientAsync(
                tcp.GetStream(), identity, peers.Trusts, linked.Token);

            return new ControlConnection(stream, PinnedTls.FingerprintOf(stream), endpoint);
        }
        catch
        {
            tcp.Dispose();
            throw;
        }
    }
}
