using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Discovery;

public class CachedEndpointStrategyTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-s1-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private static LocalNetwork Network(string key = "test-net") =>
        new(IPAddress.Parse("192.168.1.50"), IPAddress.Parse("192.168.1.1"), 24, key);

    [Fact]
    public async Task Returns_null_when_nothing_is_cached()
    {
        var probe = new FakeProbe("192.168.1.9:53321");
        var strategy = new CachedEndpointStrategy(new EndpointCache(_dir), probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }

    [Fact]
    public async Task Probes_the_cached_endpoint_and_returns_the_peer()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("test-net", new IPEndPoint(IPAddress.Parse("192.168.1.9"), 53321));

        var probe = new FakeProbe("192.168.1.9:53321");
        var strategy = new CachedEndpointStrategy(cache, probe.Probe);

        var found = await strategy.FindAsync(Network(), CancellationToken.None);

        Assert.NotNull(found);
        Assert.Equal("192.168.1.9", found.Endpoint.Address.ToString());
        Assert.Single(probe.Attempts);
    }

    [Fact]
    public async Task Returns_null_when_the_cached_endpoint_no_longer_answers()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("test-net", new IPEndPoint(IPAddress.Parse("192.168.1.9"), 53321));

        var probe = new FakeProbe(); // nothing responds
        var strategy = new CachedEndpointStrategy(cache, probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(), CancellationToken.None));
    }

    [Fact]
    public async Task Only_uses_the_entry_for_the_current_network()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("other-net", new IPEndPoint(IPAddress.Parse("192.168.1.9"), 53321));

        var probe = new FakeProbe("192.168.1.9:53321");
        var strategy = new CachedEndpointStrategy(cache, probe.Probe);

        Assert.Null(await strategy.FindAsync(Network("test-net"), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }

    [Fact]
    public async Task Skips_a_cached_endpoint_that_is_not_a_local_address()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("test-net", new IPEndPoint(IPAddress.Parse("8.8.8.8"), 53321));

        var probe = new FakeProbe("8.8.8.8:53321");
        var strategy = new CachedEndpointStrategy(cache, probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }
}
