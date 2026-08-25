using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Discovery;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Discovery;

public class DiscoveryCoordinatorTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-coord-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private sealed class StubNetworkInfo(LocalNetwork? network) : INetworkInfo
    {
        public LocalNetwork? Current() => network;
    }

    private sealed class StubStrategy(string name, int delayMs, DiscoveredPeer? result) : IDiscoveryStrategy
    {
        public string Name => name;
        public bool WasCancelled { get; private set; }

        public async Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken)
        {
            try
            {
                await Task.Delay(delayMs, cancellationToken);
                return result;
            }
            catch (OperationCanceledException)
            {
                WasCancelled = true;
                throw;
            }
        }
    }

    private sealed class ThrowingStrategy : IDiscoveryStrategy
    {
        public string Name => "throwing";
        public Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken) =>
            throw new SocketException(10013);
    }

    private static LocalNetwork Network() =>
        new(IPAddress.Parse("192.168.1.50"), IPAddress.Parse("192.168.1.1"), 24, "test-net");

    private static DiscoveredPeer Peer(string address) =>
        new(FakeProbe.Peer, new IPEndPoint(IPAddress.Parse(address), 53321));

    [Fact]
    public async Task Returns_the_fastest_strategys_result()
    {
        var fast = new StubStrategy("fast", 50, Peer("192.168.1.9"));
        var slow = new StubStrategy("slow", 3000, Peer("192.168.1.10"));

        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), new EndpointCache(_dir), [slow, fast]);

        var result = await coordinator.DiscoverAsync(TimeSpan.FromSeconds(10), CancellationToken.None);

        Assert.NotNull(result);
        Assert.Equal("fast", result.StrategyName);
        Assert.Equal("192.168.1.9", result.Peer.Endpoint.Address.ToString());
    }

    [Fact]
    public async Task Cancels_the_losing_strategies()
    {
        var fast = new StubStrategy("fast", 50, Peer("192.168.1.9"));
        var slow = new StubStrategy("slow", 5000, Peer("192.168.1.10"));

        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), new EndpointCache(_dir), [slow, fast]);

        await coordinator.DiscoverAsync(TimeSpan.FromSeconds(10), CancellationToken.None);

        Assert.True(slow.WasCancelled);
    }

    [Fact]
    public async Task Caches_the_winning_endpoint_under_the_network_key()
    {
        var cache = new EndpointCache(_dir);
        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), cache,
            [new StubStrategy("fast", 10, Peer("192.168.1.9"))]);

        await coordinator.DiscoverAsync(TimeSpan.FromSeconds(5), CancellationToken.None);

        Assert.Equal(IPAddress.Parse("192.168.1.9"), cache.Get("test-net")!.Address);
    }

    [Fact]
    public async Task Returns_null_when_every_strategy_finds_nothing()
    {
        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), new EndpointCache(_dir),
            [new StubStrategy("a", 10, null), new StubStrategy("b", 20, null)]);

        Assert.Null(await coordinator.DiscoverAsync(TimeSpan.FromSeconds(5), CancellationToken.None));
    }

    [Fact]
    public async Task A_throwing_strategy_does_not_prevent_another_from_winning()
    {
        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), new EndpointCache(_dir),
            [new ThrowingStrategy(), new StubStrategy("good", 50, Peer("192.168.1.9"))]);

        var result = await coordinator.DiscoverAsync(TimeSpan.FromSeconds(5), CancellationToken.None);

        Assert.NotNull(result);
        Assert.Equal("good", result.StrategyName);
    }

    [Fact]
    public async Task Returns_null_when_offline()
    {
        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(null), new EndpointCache(_dir),
            [new StubStrategy("fast", 10, Peer("192.168.1.9"))]);

        Assert.Null(await coordinator.DiscoverAsync(TimeSpan.FromSeconds(5), CancellationToken.None));
    }

    [Fact]
    public async Task Returns_null_when_the_timeout_expires()
    {
        var coordinator = new DiscoveryCoordinator(
            new StubNetworkInfo(Network()), new EndpointCache(_dir),
            [new StubStrategy("slow", 10000, Peer("192.168.1.9"))]);

        Assert.Null(await coordinator.DiscoverAsync(TimeSpan.FromMilliseconds(200), CancellationToken.None));
    }
}
