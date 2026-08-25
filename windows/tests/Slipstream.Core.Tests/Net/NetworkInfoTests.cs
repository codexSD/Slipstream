using System.Net;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Net;

public class SubnetMathTests
{
    [Fact]
    public void Enumerates_254_hosts_for_a_slash_24()
    {
        var hosts = SubnetMath.EnumerateHosts(IPAddress.Parse("192.168.1.37"), 24).ToList();

        Assert.Equal(254, hosts.Count);
        Assert.Equal(IPAddress.Parse("192.168.1.1"), hosts[0]);
        Assert.Equal(IPAddress.Parse("192.168.1.254"), hosts[^1]);
    }

    [Fact]
    public void Excludes_the_network_and_broadcast_addresses()
    {
        var hosts = SubnetMath.EnumerateHosts(IPAddress.Parse("192.168.1.37"), 24).ToList();

        Assert.DoesNotContain(IPAddress.Parse("192.168.1.0"), hosts);
        Assert.DoesNotContain(IPAddress.Parse("192.168.1.255"), hosts);
    }

    [Fact]
    public void Refuses_to_sweep_anything_wider_than_a_slash_24()
    {
        Assert.Empty(SubnetMath.EnumerateHosts(IPAddress.Parse("10.0.0.1"), 16));
        Assert.Empty(SubnetMath.EnumerateHosts(IPAddress.Parse("10.0.0.1"), 8));
    }

    [Fact]
    public void Handles_a_prefix_narrower_than_a_slash_24()
    {
        var hosts = SubnetMath.EnumerateHosts(IPAddress.Parse("192.168.1.130"), 25).ToList();

        Assert.Equal(126, hosts.Count);
        Assert.Equal(IPAddress.Parse("192.168.1.129"), hosts[0]);
        Assert.Equal(IPAddress.Parse("192.168.1.254"), hosts[^1]);
    }
}

public class NetworkInfoTests
{
    [Fact]
    public void Current_returns_a_local_address_or_null_when_offline()
    {
        var current = new NetworkInfo().Current();

        if (current is null) return; // CI runners are sometimes networkless.

        Assert.True(LanGuard.IsLocal(current.LocalAddress));
        Assert.False(string.IsNullOrWhiteSpace(current.Key));
        if (current.Gateway is not null) Assert.True(LanGuard.IsLocal(current.Gateway));
    }
}
