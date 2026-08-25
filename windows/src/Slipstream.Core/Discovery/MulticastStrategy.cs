using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S3. Announces to the multicast group and listens for the paired peer.
/// A peer that receives a query replies by unicast, which is the fallback for
/// networks that deliver multicast in one direction only.
/// </summary>
public sealed class MulticastStrategy : IDiscoveryStrategy, IAsyncDisposable
{
    private static readonly TimeSpan AnnounceInterval = TimeSpan.FromMilliseconds(700);

    private readonly DeviceIdentity _identity;
    private readonly PairedPeerStore _peers;
    private readonly PeerProbe _probe;
    private readonly UdpClient _listener;

    public MulticastStrategy(
        DeviceIdentity identity,
        PairedPeerStore peers,
        PeerProbe probe,
        int listenPort = SlipstreamPorts.Discovery)
    {
        _identity = identity;
        _peers = peers;
        _probe = probe;

        _listener = new UdpClient(AddressFamily.InterNetwork);
        _listener.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _listener.Client.Bind(new IPEndPoint(IPAddress.Any, listenPort));

        TryJoinMulticastGroup();
    }

    /// <summary>The bound listening endpoint. Tests use this to send directly.</summary>
    public IPEndPoint ListenEndPoint
    {
        get
        {
            var local = (IPEndPoint)_listener.Client.LocalEndPoint!;
            // Bound to IPAddress.Any so we accept from every interface; that address is not
            // itself a valid send target (Windows rejects sends to 0.0.0.0), so callers that
            // want to talk back to this socket need the loopback address instead.
            return Equals(local.Address, IPAddress.Any) ? new IPEndPoint(IPAddress.Loopback, local.Port) : local;
        }
    }

    public string Name => "multicast";

    public async Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken)
    {
        var peer = _peers.Current;
        if (peer is null) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

        var announcing = AnnounceRepeatedlyAsync(AnnouncementKind.Query, linked.Token);

        try
        {
            while (!linked.Token.IsCancellationRequested)
            {
                var received = await _listener.ReceiveAsync(linked.Token);

                var announcement = PeerAnnouncement.TryParse(received.Buffer);
                if (announcement is null) continue;

                // Never discover ourselves.
                if (string.Equals(announcement.Fingerprint, _identity.Fingerprint, StringComparison.OrdinalIgnoreCase))
                    continue;

                if (!_peers.Trusts(announcement.Fingerprint)) continue;
                if (!LanGuard.IsLocal(received.RemoteEndPoint.Address)) continue;

                var endpoint = new IPEndPoint(received.RemoteEndPoint.Address, announcement.ControlPort);

                var found = await _probe(endpoint, linked.Token);
                if (found is not null) return found;
            }
        }
        catch (OperationCanceledException)
        {
            // Cancelled by the coordinator because another strategy won, or by timeout.
        }
        finally
        {
            await linked.CancelAsync();
            await SwallowAsync(announcing);
        }

        return null;
    }

    /// <summary>
    /// The always-on responder: reply by unicast to any query from the paired peer.
    /// Run by the server for the lifetime of the app.
    /// </summary>
    public async Task RespondToQueriesAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                var received = await _listener.ReceiveAsync(cancellationToken);

                var announcement = PeerAnnouncement.TryParse(received.Buffer);
                if (announcement is null) continue;
                if (announcement.Kind != AnnouncementKind.Query) continue;
                if (!_peers.Trusts(announcement.Fingerprint)) continue;
                if (!LanGuard.IsLocal(received.RemoteEndPoint.Address)) continue;

                await _listener.SendAsync(Payload(AnnouncementKind.Announce), received.RemoteEndPoint, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (SocketException)
            {
                // Transient; keep listening.
            }
        }
    }

    private async Task AnnounceRepeatedlyAsync(AnnouncementKind kind, CancellationToken cancellationToken)
    {
        var group = new IPEndPoint(SlipstreamPorts.MulticastGroup, SlipstreamPorts.Discovery);
        var payload = Payload(kind);

        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await _listener.SendAsync(payload, group, cancellationToken);
                await Task.Delay(AnnounceInterval, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (SocketException)
            {
                // Multicast send fails on some adapters. That is exactly why S4 exists.
                await Task.Delay(AnnounceInterval, CancellationToken.None);
            }
        }
    }

    private byte[] Payload(AnnouncementKind kind) => new PeerAnnouncement(
        SlipstreamPorts.ProtocolVersion,
        _identity.DeviceId,
        _identity.DisplayName,
        _identity.Fingerprint,
        SlipstreamPorts.Control,
        kind).ToBytes();

    private void TryJoinMulticastGroup()
    {
        try
        {
            _listener.JoinMulticastGroup(SlipstreamPorts.MulticastGroup);
        }
        catch (SocketException)
        {
            // Some adapters refuse the join. Unicast replies still work; S4 covers the rest.
        }
    }

    private static async Task SwallowAsync(Task task)
    {
        try { await task; } catch (OperationCanceledException) { }
    }

    public ValueTask DisposeAsync()
    {
        _listener.Dispose();
        return ValueTask.CompletedTask;
    }
}
