using System.Net;
using System.Net.Sockets;
using Slipstream.Core;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingDiscoveryTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-pairdisc-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(25));

    private readonly DeviceIdentity _local = DeviceIdentity.CreateNew("Local PC");

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    private MulticastStrategy NewMulticast() =>
        new(_local, new PairedPeerStore(_dir), (_, _) => Task.FromResult<DiscoveredPeer?>(null), listenPort: 0);

    private static async Task SendAsync(IPEndPoint target, PeerAnnouncement announcement)
    {
        await Task.Delay(250);
        using var sender = new UdpClient(AddressFamily.InterNetwork);
        await sender.SendAsync(announcement.ToBytes(), target);
    }

    private static PeerAnnouncement Announcement(string deviceId, string name, string fingerprint) =>
        new(SlipstreamPorts.ProtocolVersion, deviceId, name, fingerprint,
            SlipstreamPorts.Control, AnnouncementKind.Announce);

    [Fact]
    public async Task Returns_null_immediately_when_the_window_is_closed()
    {
        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, new PairingWindow());

        var found = await discovery.FindAsync(TimeSpan.FromSeconds(5), _cts.Token);

        Assert.Null(found);
    }

    [Fact]
    public async Task Finds_an_unpaired_peer_while_the_window_is_open()
    {
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window);

        var find = discovery.FindAsync(TimeSpan.FromSeconds(15), _cts.Token);
        await SendAsync(multicast.ListenEndPoint, Announcement("stranger-id", "Stranger Phone", "cafebabe"));

        var found = await find;

        Assert.NotNull(found);
        Assert.Equal("stranger-id", found.DeviceId);
        Assert.Equal("Stranger Phone", found.DisplayName);
        Assert.Equal("cafebabe", found.Fingerprint);
        Assert.Equal(SlipstreamPorts.Control, found.Endpoint.Port);
    }

    [Fact]
    public async Task Ignores_our_own_announcement()
    {
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window);

        var find = discovery.FindAsync(TimeSpan.FromSeconds(4), _cts.Token);
        await SendAsync(multicast.ListenEndPoint,
            Announcement(_local.DeviceId, _local.DisplayName, _local.Fingerprint));

        Assert.Null(await find);
    }

    [Fact]
    public async Task Returns_null_when_nothing_announces_before_the_timeout()
    {
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window);

        Assert.Null(await discovery.FindAsync(TimeSpan.FromSeconds(2), _cts.Token));
    }

    [Fact]
    public async Task Ignores_malformed_datagrams_and_keeps_looking()
    {
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window);

        var find = discovery.FindAsync(TimeSpan.FromSeconds(15), _cts.Token);

        await Task.Delay(250, _cts.Token);
        using (var junk = new UdpClient(AddressFamily.InterNetwork))
        {
            await junk.SendAsync("garbage"u8.ToArray(), multicast.ListenEndPoint);
        }

        await SendAsync(multicast.ListenEndPoint, Announcement("stranger-id", "Stranger", "cafebabe"));

        Assert.NotNull(await find);
    }

    [Fact]
    public async Task The_paired_discovery_path_still_works_alongside_a_pairing_subscription()
    {
        // Regression guard for the bug Plan 1 fixed: adding a subscriber must not
        // starve the existing responder or FindAsync paths.
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window);

        var pairingFind = discovery.FindAsync(TimeSpan.FromSeconds(15), _cts.Token);
        var responder = multicast.RespondToQueriesAsync(_cts.Token);

        await SendAsync(multicast.ListenEndPoint, Announcement("stranger-id", "Stranger", "cafebabe"));

        Assert.NotNull(await pairingFind);
        Assert.False(responder.IsFaulted);
    }
}
