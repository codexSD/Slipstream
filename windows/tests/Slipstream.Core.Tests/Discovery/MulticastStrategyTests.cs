using System.Net;
using System.Net.Sockets;
using Slipstream.Core;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Discovery;

public class MulticastStrategyTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-s3-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private static LocalNetwork Network() =>
        new(IPAddress.Loopback, IPAddress.Parse("127.0.0.1"), 24, "loopback");

    private (DeviceIdentity Identity, PairedPeerStore Peers) Paired(string peerFingerprint)
    {
        var identity = DeviceIdentity.CreateNew("Test PC");
        var peers = new PairedPeerStore(_dir);
        peers.Pair(new PairedPeer("peer-device", peerFingerprint, "Test Phone", DateTimeOffset.UnixEpoch));
        return (identity, peers);
    }

    [Fact]
    public async Task Returns_null_when_unpaired()
    {
        var identity = DeviceIdentity.CreateNew("Test PC");
        var peers = new PairedPeerStore(_dir); // never paired
        var probe = new FakeProbe();

        await using var strategy = new MulticastStrategy(identity, peers, probe.Probe, listenPort: 0);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        Assert.Null(await strategy.FindAsync(Network(), cts.Token));
    }

    [Fact]
    public async Task Probes_a_peer_that_announces_with_the_trusted_fingerprint()
    {
        var (identity, peers) = Paired("deadbeef");
        var probe = new FakeProbe("127.0.0.1:53321");

        await using var strategy = new MulticastStrategy(identity, peers, probe.Probe, listenPort: 0);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var find = strategy.FindAsync(Network(), cts.Token);

        await SendUnicastAsync(strategy.ListenEndPoint, new PeerAnnouncement(
            SlipstreamPorts.ProtocolVersion, "peer-device", "Test Phone",
            "deadbeef", SlipstreamPorts.Control, AnnouncementKind.Announce));

        var found = await find;

        Assert.NotNull(found);
        Assert.Equal(53321, found.Endpoint.Port);
    }

    [Fact]
    public async Task Ignores_an_announcement_from_an_untrusted_fingerprint()
    {
        var (identity, peers) = Paired("deadbeef");
        var probe = new FakeProbe("127.0.0.1:53321");

        await using var strategy = new MulticastStrategy(identity, peers, probe.Probe, listenPort: 0);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        var find = strategy.FindAsync(Network(), cts.Token);

        await SendUnicastAsync(strategy.ListenEndPoint, new PeerAnnouncement(
            SlipstreamPorts.ProtocolVersion, "stranger", "Someone Else",
            "cafebabe", SlipstreamPorts.Control, AnnouncementKind.Announce));

        Assert.Null(await find);
        Assert.Empty(probe.Attempts);
    }

    [Fact]
    public async Task Ignores_its_own_announcement()
    {
        var (identity, peers) = Paired("deadbeef");
        var probe = new FakeProbe("127.0.0.1:53321");

        await using var strategy = new MulticastStrategy(identity, peers, probe.Probe, listenPort: 0);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        var find = strategy.FindAsync(Network(), cts.Token);

        await SendUnicastAsync(strategy.ListenEndPoint, new PeerAnnouncement(
            SlipstreamPorts.ProtocolVersion, identity.DeviceId, identity.DisplayName,
            identity.Fingerprint, SlipstreamPorts.Control, AnnouncementKind.Announce));

        Assert.Null(await find);
    }

    [Fact]
    public async Task Ignores_malformed_datagrams_without_failing()
    {
        var (identity, peers) = Paired("deadbeef");
        var probe = new FakeProbe("127.0.0.1:53321");

        await using var strategy = new MulticastStrategy(identity, peers, probe.Probe, listenPort: 0);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var find = strategy.FindAsync(Network(), cts.Token);

        using (var junk = new UdpClient(AddressFamily.InterNetwork))
        {
            await junk.SendAsync("garbage"u8.ToArray(), strategy.ListenEndPoint);
        }

        await SendUnicastAsync(strategy.ListenEndPoint, new PeerAnnouncement(
            SlipstreamPorts.ProtocolVersion, "peer-device", "Test Phone",
            "deadbeef", SlipstreamPorts.Control, AnnouncementKind.Announce));

        Assert.NotNull(await find);
    }

    private static async Task SendUnicastAsync(IPEndPoint target, PeerAnnouncement announcement)
    {
        await Task.Delay(200); // let the listener bind
        using var sender = new UdpClient(AddressFamily.InterNetwork);
        await sender.SendAsync(announcement.ToBytes(), target);
    }
}
