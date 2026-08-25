using System.Net;
using System.Net.Sockets;
using System.Threading.Channels;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S3. Announces to the multicast group and listens for the paired peer.
/// A peer that receives a query replies by unicast, which is the fallback for
/// networks that deliver multicast in one direction only.
/// </summary>
/// <remarks>
/// Exactly one background loop ever calls <see cref="UdpClient.ReceiveAsync(CancellationToken)"/>
/// on <see cref="_listener"/>. <see cref="RespondToQueriesAsync"/> (started once, for the app's
/// lifetime) and <see cref="FindAsync"/> (invoked per discovery attempt, possibly while the
/// responder is already running) both need to observe inbound datagrams, but .NET delivers each
/// datagram to exactly one pending <c>ReceiveAsync</c> call. Two independent receive loops on the
/// same socket would therefore race to "steal" each other's datagrams. Instead, the single loop
/// parses each datagram once and fans it out: it answers queries inline (always-on, regardless of
/// whether a find is in progress) and publishes every parsed announcement to any active
/// <see cref="FindAsync"/> subscriber, so no datagram is ever dropped by one path when the other
/// needed it.
/// </remarks>
public sealed class MulticastStrategy : IDiscoveryStrategy, IAsyncDisposable
{
    private static readonly TimeSpan AnnounceInterval = TimeSpan.FromMilliseconds(700);

    private readonly DeviceIdentity _identity;
    private readonly PairedPeerStore _peers;
    private readonly PeerProbe _probe;
    private readonly UdpClient _listener;

    private readonly CancellationTokenSource _loopCts = new();
    private readonly object _startLock = new();
    private readonly object _subscriberLock = new();
    private readonly List<Channel<(PeerAnnouncement Announcement, IPEndPoint RemoteEndPoint)>> _subscribers = new();
    private Task? _receiveLoopTask;
    private bool _disposed;

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

        EnsureReceiveLoopStarted();

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

        var channel = Channel.CreateUnbounded<(PeerAnnouncement Announcement, IPEndPoint RemoteEndPoint)>();
        Subscribe(channel);

        var announcing = AnnounceRepeatedlyAsync(AnnouncementKind.Query, linked.Token);

        try
        {
            while (await channel.Reader.WaitToReadAsync(linked.Token))
            {
                while (channel.Reader.TryRead(out var item))
                {
                    var (announcement, remoteEndPoint) = item;

                    // Never discover ourselves.
                    if (string.Equals(announcement.Fingerprint, _identity.Fingerprint, StringComparison.OrdinalIgnoreCase))
                        continue;

                    if (!_peers.Trusts(announcement.Fingerprint)) continue;
                    if (!LanGuard.IsLocal(remoteEndPoint.Address)) continue;

                    var endpoint = new IPEndPoint(remoteEndPoint.Address, announcement.ControlPort);

                    var found = await _probe(endpoint, linked.Token);
                    if (found is not null) return found;
                }
            }
        }
        catch (OperationCanceledException)
        {
            // Cancelled by the coordinator because another strategy won, or by timeout.
        }
        finally
        {
            Unsubscribe(channel);
            await linked.CancelAsync();
            await SwallowAsync(announcing);
        }

        return null;
    }

    /// <summary>
    /// Subscribes to every parsed announcement from the shared reader loop, regardless of
    /// trust — filtering by trust (or lack of it) is the caller's job, not the reader's.
    /// Used by pairing discovery, which must see unpaired peers that <see cref="FindAsync"/>
    /// would filter out.
    /// </summary>
    public async IAsyncEnumerable<(PeerAnnouncement Announcement, IPEndPoint Source)> SubscribeAsync(
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
    {
        EnsureReceiveLoopStarted();

        var channel = Channel.CreateUnbounded<(PeerAnnouncement Announcement, IPEndPoint RemoteEndPoint)>();
        Subscribe(channel);

        try
        {
            while (await channel.Reader.WaitToReadAsync(cancellationToken))
            {
                while (channel.Reader.TryRead(out var item))
                    yield return item;
            }
        }
        finally
        {
            Unsubscribe(channel);
        }
    }

    /// <summary>
    /// The always-on responder: reply by unicast to any query from the paired peer.
    /// Run by the server for the lifetime of the app. The actual query handling happens
    /// inline in the shared receive loop (see <see cref="ReceiveLoopAsync"/>) so it can never
    /// be starved by a concurrent <see cref="FindAsync"/>; this method just ensures that loop
    /// is running and stays alive for as long as the caller wants the responder active.
    /// </summary>
    public async Task RespondToQueriesAsync(CancellationToken cancellationToken)
    {
        EnsureReceiveLoopStarted();

        try
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            // Cancelled by the app shutting down.
        }
    }

    private void EnsureReceiveLoopStarted()
    {
        if (_receiveLoopTask is not null) return;
        lock (_startLock)
        {
            _receiveLoopTask ??= Task.Run(() => ReceiveLoopAsync(_loopCts.Token));
        }
    }

    /// <summary>
    /// The single reader of the socket. Parses each datagram exactly once, answers queries
    /// inline, and fans every parsed announcement out to active <see cref="FindAsync"/> callers.
    /// </summary>
    private async Task ReceiveLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            UdpReceiveResult received;
            try
            {
                received = await _listener.ReceiveAsync(cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException)
            {
                // Transient; keep listening.
                continue;
            }

            var announcement = PeerAnnouncement.TryParse(received.Buffer);
            if (announcement is null) continue;

            if (announcement.Kind == AnnouncementKind.Query
                && !string.Equals(announcement.Fingerprint, _identity.Fingerprint, StringComparison.OrdinalIgnoreCase)
                && _peers.Trusts(announcement.Fingerprint)
                && LanGuard.IsLocal(received.RemoteEndPoint.Address))
            {
                try
                {
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

            Publish(announcement, received.RemoteEndPoint);
        }
    }

    private void Publish(PeerAnnouncement announcement, IPEndPoint remoteEndPoint)
    {
        List<Channel<(PeerAnnouncement Announcement, IPEndPoint RemoteEndPoint)>> subscribers;
        lock (_subscriberLock)
        {
            if (_subscribers.Count == 0) return;
            subscribers = new(_subscribers);
        }

        foreach (var subscriber in subscribers)
            subscriber.Writer.TryWrite((announcement, remoteEndPoint));
    }

    private void Subscribe(Channel<(PeerAnnouncement Announcement, IPEndPoint RemoteEndPoint)> channel)
    {
        lock (_subscriberLock) _subscribers.Add(channel);
    }

    private void Unsubscribe(Channel<(PeerAnnouncement Announcement, IPEndPoint RemoteEndPoint)> channel)
    {
        lock (_subscriberLock) _subscribers.Remove(channel);
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

    public async ValueTask DisposeAsync()
    {
        // Idempotent: a SlipstreamPeer may legitimately be disposed by more than one
        // owner (e.g. a PeerHost that took over its lifetime, and a test rig that also
        // holds a reference) — the second call must be a no-op, not a crash.
        if (_disposed) return;
        _disposed = true;

        await _loopCts.CancelAsync();
        _listener.Dispose();

        var loopTask = _receiveLoopTask;
        if (loopTask is not null)
            await SwallowAsync(loopTask);

        _loopCts.Dispose();
    }
}
