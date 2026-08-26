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
        Assert.Equal(253, probe.Attempts.Count);
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
        // Measures the property directly — the peak number of probes simultaneously in
        // flight — rather than inferring it from elapsed time. The wall-clock version of
        // this assertion (serial = 253 * 50ms = 12.65s, so "must finish under 2s") failed
        // under a loaded full-suite run even though the sweep was perfectly concurrent:
        // it was really measuring how busy the machine was. A serial sweep cannot exceed a
        // peak of 1 no matter how fast or slow the machine is.
        var probe = new FakeProbe("192.168.1.254:53321") { Delay = 50 };
        var strategy = new SubnetSweepStrategy(probe.Probe);

        var found = await strategy.FindAsync(Network(), CancellationToken.None);

        Assert.NotNull(found);
        Assert.True(probe.PeakInFlight >= 50,
            $"Peak concurrent probes was {probe.PeakInFlight} of 253 hosts — probes are not running concurrently.");
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
