using System.Diagnostics;
using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Discovery;

public class SubnetSweepStrategyTests
{
    private static LocalNetwork Network(int prefixLength = 24) =>
        new(IPAddress.Parse("192.168.1.50"), IPAddress.Parse("192.168.1.1"), prefixLength, "test-net");

    [Fact]
    public async Task Finds_a_peer_anywhere_in_the_subnet()
    {
        var probe = new FakeProbe("192.168.1.200:53321");
        var strategy = new SubnetSweepStrategy(probe.Probe);

        var found = await strategy.FindAsync(Network(), CancellationToken.None);

        Assert.NotNull(found);
        Assert.Equal("192.168.1.200", found.Endpoint.Address.ToString());
    }

    [Fact]
    public async Task Returns_null_when_nothing_answers()
    {
        var probe = new FakeProbe();
        var strategy = new SubnetSweepStrategy(probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(), CancellationToken.None));
        Assert.Equal(254, probe.Attempts.Count);
    }

    [Fact]
    public async Task Refuses_to_sweep_a_subnet_wider_than_a_slash_24()
    {
        var probe = new FakeProbe("10.5.5.5:53321");
        var strategy = new SubnetSweepStrategy(probe.Probe);

        Assert.Null(await strategy.FindAsync(Network(prefixLength: 16), CancellationToken.None));
        Assert.Empty(probe.Attempts);
    }

    [Fact]
    public async Task Runs_probes_concurrently_rather_than_serially()
    {
        var probe = new FakeProbe("192.168.1.254:53321") { Delay = 50 };
        var strategy = new SubnetSweepStrategy(probe.Probe);

        var stopwatch = Stopwatch.StartNew();
        var found = await strategy.FindAsync(Network(), CancellationToken.None);
        stopwatch.Stop();

        Assert.NotNull(found);
        // Serial would be 254 * 50ms = 12.7s. Concurrent should finish in well under 2s.
        Assert.True(stopwatch.ElapsedMilliseconds < 2000,
            $"Sweep took {stopwatch.ElapsedMilliseconds}ms — probes are not running concurrently.");
    }

    [Fact]
    public async Task Stops_early_when_cancelled()
    {
        var probe = new FakeProbe { Delay = 200 };
        var strategy = new SubnetSweepStrategy(probe.Probe);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(100));

        Assert.Null(await strategy.FindAsync(Network(), cts.Token));
    }
}
