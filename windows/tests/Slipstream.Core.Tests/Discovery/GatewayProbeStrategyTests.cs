using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Discovery;

public class GatewayProbeStrategyTests
{
    private static LocalNetwork Network(string? gateway) =>
        new(IPAddress.Parse("192.168.43.100"),
            gateway is null ? null : IPAddress.Parse(gateway),
            24,
            "hotspot");

    [Fact]
    public async Task Finds_the_peer_at_the_gateway_address()
    {
        // The Android hotspot case: the phone is the gateway.
        var probe = new FakeProbe("192.168.43.1:53321");
        var strategy = new GatewayProbeStrategy(probe.Probe);

        var found = await strategy.FindAsync(Network("192.168.43.1"), CancellationToken.None);

        Assert.NotNull(found);
        Assert.Equal("192.168.43.1", found.Endpoint.Address.ToString());
        Assert.Equal(53321, found.Endpoint.Port);
    }

    [Fact]
    public async Task Probes_exactly_once()
    {
        var probe = new FakeProbe("192.168.43.1:53321");
        await new GatewayProbeStrategy(probe.Probe).FindAsync(Network("192.168.43.1"), CancellationToken.None);

        Assert.Single(probe.Attempts);
    }

    [Fact]
    public async Task Returns_null_when_there_is_no_gateway()
    {
        var probe = new FakeProbe("192.168.43.1:53321");
        var strategy = new GatewayProbeStrategy(probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(gateway: null), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }

    [Fact]
    public async Task Returns_null_when_the_gateway_is_a_router_rather_than_the_peer()
    {
        // External WiFi: the gateway is a router that does not speak Slipstream.
        var probe = new FakeProbe("10.0.0.7:53321");
        var strategy = new GatewayProbeStrategy(probe.Probe);

        Assert.Null(await strategy.FindAsync(Network("10.0.0.1"), CancellationToken.None));
    }

    [Fact]
    public async Task Refuses_a_non_local_gateway()
    {
        var probe = new FakeProbe("8.8.8.8:53321");
        var strategy = new GatewayProbeStrategy(probe.Probe);

        Assert.Null(await strategy.FindAsync(Network("8.8.8.8"), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }
}
