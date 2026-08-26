using System.Net;
using System.Net.Sockets;
using Slipstream.Core;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;
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

    /// <summary>A machine with no usable local network: the multicast-only tests below then
    /// exercise exactly one strategy, as they did before the ladder existed.</summary>
    private sealed class NoRoutes : INetworkInfo
    {
        public LocalNetwork? Current() => null;
    }

    private sealed class FixedNetwork(LocalNetwork? network) : INetworkInfo
    {
        public LocalNetwork? Current() => network;
    }

    /// <summary>Records every endpoint it was asked about, and answers for at most one.</summary>
    private sealed class RecordingProbe(IPEndPoint? answersFor)
    {
        private readonly List<IPEndPoint> _seen = [];
        private readonly object _gate = new();

        public IReadOnlyList<IPEndPoint> Seen
        {
            get { lock (_gate) return [.. _seen]; }
        }

        public PairingProbe Delegate => (endpoint, _) =>
        {
            lock (_gate) _seen.Add(endpoint);

            return Task.FromResult(
                answersFor is not null && endpoint.Equals(answersFor)
                    ? new UnpairedPeer("gw-id", "Gateway Phone", "deadbeef", endpoint)
                    : null);
        };
    }

    private static PairingProbe NeverFinds => (_, _) => Task.FromResult<UnpairedPeer?>(null);

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
        var discovery = new PairingDiscovery(_local, multicast, new PairingWindow(), new NoRoutes(), NeverFinds);

        var found = await discovery.FindAsync(TimeSpan.FromSeconds(5), _cts.Token);

        Assert.Null(found);
    }

    [Fact]
    public async Task Finds_an_unpaired_peer_while_the_window_is_open()
    {
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window, new NoRoutes(), NeverFinds);

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
        var discovery = new PairingDiscovery(_local, multicast, window, new NoRoutes(), NeverFinds);

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
        var discovery = new PairingDiscovery(_local, multicast, window, new NoRoutes(), NeverFinds);

        Assert.Null(await discovery.FindAsync(TimeSpan.FromSeconds(2), _cts.Token));
    }

    [Fact]
    public async Task Ignores_malformed_datagrams_and_keeps_looking()
    {
        var window = new PairingWindow();
        window.Open();

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(_local, multicast, window, new NoRoutes(), NeverFinds);

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
        var discovery = new PairingDiscovery(_local, multicast, window, new NoRoutes(), NeverFinds);

        var pairingFind = discovery.FindAsync(TimeSpan.FromSeconds(15), _cts.Token);
        var responder = multicast.RespondToQueriesAsync(_cts.Token);

        await SendAsync(multicast.ListenEndPoint, Announcement("stranger-id", "Stranger", "cafebabe"));

        Assert.NotNull(await pairingFind);
        Assert.False(responder.IsFaulted);
    }

    // --- The strategy ladder (spec 1: the phone hotspot is the primary topology) ---

    private static LocalNetwork Hotspot() => new(
        IPAddress.Parse("10.199.176.201"), IPAddress.Parse("10.199.176.137"), 24, "hotspot");

    [Fact]
    public async Task Finds_an_unpaired_peer_by_gateway_probe_when_multicast_yields_nothing()
    {
        // The phone-hotspot case, reproduced: the phone is the PC's default gateway and its
        // softAP never delivers our multicast. Discovery must still find it, instantly.
        var window = new PairingWindow();
        window.Open();

        var gateway = new IPEndPoint(IPAddress.Parse("10.199.176.137"), SlipstreamPorts.Control);
        var probe = new RecordingProbe(answersFor: gateway);

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(
            _local, multicast, window, new FixedNetwork(Hotspot()), probe.Delegate,
            sweepProbe: NeverFinds);

        // No datagram is ever sent: multicast finds nothing, exactly as on real hardware.
        var found = await discovery.FindAsync(TimeSpan.FromSeconds(10), _cts.Token);

        Assert.NotNull(found);
        Assert.Equal("deadbeef", found.Fingerprint);
        Assert.Equal(gateway, found.Endpoint);
        Assert.Contains(gateway, probe.Seen);
    }

    [Fact]
    public async Task Finds_an_unpaired_peer_by_subnet_sweep_when_there_is_no_gateway()
    {
        var window = new PairingWindow();
        window.Open();

        var target = new IPEndPoint(IPAddress.Parse("192.168.4.9"), SlipstreamPorts.Control);
        var probe = new RecordingProbe(answersFor: target);
        var network = new LocalNetwork(IPAddress.Parse("192.168.4.2"), null, 24, "flat");

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(
            _local, multicast, window, new FixedNetwork(network), NeverFinds,
            sweepProbe: probe.Delegate);

        var found = await discovery.FindAsync(TimeSpan.FromSeconds(10), _cts.Token);

        Assert.NotNull(found);
        Assert.Equal(target, found.Endpoint);

        // Bounded to the /24, and never probing ourselves.
        Assert.All(probe.Seen, seen => Assert.StartsWith("192.168.4.", seen.Address.ToString()));
        Assert.DoesNotContain(IPAddress.Parse("192.168.4.2"), probe.Seen.Select(e => e.Address));
    }

    [Fact]
    public async Task Multicast_still_wins_when_it_works_even_with_the_ladder_running()
    {
        var window = new PairingWindow();
        window.Open();

        var probe = new RecordingProbe(answersFor: null);

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(
            _local, multicast, window, new FixedNetwork(Hotspot()), probe.Delegate,
            sweepProbe: probe.Delegate);

        var find = discovery.FindAsync(TimeSpan.FromSeconds(15), _cts.Token);
        await SendAsync(multicast.ListenEndPoint, Announcement("stranger-id", "Stranger Phone", "cafebabe"));

        var found = await find;

        Assert.NotNull(found);
        Assert.Equal("stranger-id", found.DeviceId);
        Assert.Equal("cafebabe", found.Fingerprint);
    }

    [Fact]
    public async Task Nothing_probes_at_all_while_the_pairing_window_is_closed()
    {
        // The whole security argument: outside the window we do not touch the network.
        var probe = new RecordingProbe(
            answersFor: new IPEndPoint(IPAddress.Parse("10.199.176.137"), SlipstreamPorts.Control));

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(
            _local, multicast, new PairingWindow(), new FixedNetwork(Hotspot()),
            probe.Delegate, sweepProbe: probe.Delegate);

        var found = await discovery.FindAsync(TimeSpan.FromSeconds(5), _cts.Token);

        Assert.Null(found);
        Assert.Empty(probe.Seen);
    }

    [Fact]
    public async Task Stops_searching_the_moment_the_window_closes_mid_search()
    {
        var window = new PairingWindow();
        window.Open();

        var probe = new RecordingProbe(answersFor: null);

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(
            _local, multicast, window, new FixedNetwork(Hotspot()), probe.Delegate,
            sweepProbe: probe.Delegate);

        var find = discovery.FindAsync(TimeSpan.FromSeconds(10), _cts.Token);
        window.Close();

        Assert.Null(await find);
    }

    [Fact]
    public async Task A_peer_found_by_the_gateway_probe_is_a_candidate_and_never_a_pairing()
    {
        // Discovery hands back an address and a fingerprint. It does not pair and it does
        // not persist: mutual six-digit confirmation is PairingCoordinator's job alone.
        var window = new PairingWindow();
        window.Open();

        var gateway = new IPEndPoint(IPAddress.Parse("10.199.176.137"), SlipstreamPorts.Control);
        var probe = new RecordingProbe(answersFor: gateway);
        var peers = new PairedPeerStore(_dir);

        await using var multicast = NewMulticast();
        var discovery = new PairingDiscovery(
            _local, multicast, window, new FixedNetwork(Hotspot()), probe.Delegate,
            sweepProbe: NeverFinds);

        var found = await discovery.FindAsync(TimeSpan.FromSeconds(10), _cts.Token);

        Assert.NotNull(found);
        Assert.False(peers.IsPaired);
        Assert.Null(peers.Current);
        Assert.False(peers.Trusts(found.Fingerprint));
    }
}
