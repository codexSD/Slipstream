using System.Net;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Net;

public class LanGuardTests
{
    [Theory]
    [InlineData("10.0.0.1")]
    [InlineData("10.255.255.254")]
    [InlineData("172.16.0.1")]
    [InlineData("172.31.255.254")]
    [InlineData("192.168.1.1")]
    [InlineData("192.168.43.1")]   // the Android hotspot gateway
    [InlineData("169.254.10.20")]  // link-local
    [InlineData("127.0.0.1")]      // loopback, needed for same-machine tests
    [InlineData("::1")]
    [InlineData("fe80::1")]
    public void IsLocal_accepts_private_and_link_local(string address)
    {
        Assert.True(LanGuard.IsLocal(IPAddress.Parse(address)));
    }

    [Theory]
    [InlineData("8.8.8.8")]
    [InlineData("1.1.1.1")]
    [InlineData("172.15.255.255")] // just below the 172.16/12 block
    [InlineData("172.32.0.1")]     // just above it
    [InlineData("192.167.1.1")]    // near-miss on 192.168/16
    [InlineData("11.0.0.1")]       // near-miss on 10/8
    [InlineData("2001:4860:4860::8888")]
    public void IsLocal_rejects_public_addresses(string address)
    {
        Assert.False(LanGuard.IsLocal(IPAddress.Parse(address)));
    }

    [Fact]
    public void EnsureLocal_throws_for_public_address()
    {
        var ex = Assert.Throws<NonLocalAddressException>(
            () => LanGuard.EnsureLocal(IPAddress.Parse("8.8.8.8")));
        Assert.Contains("8.8.8.8", ex.Message);
    }

    [Fact]
    public void EnsureLocal_passes_for_private_address()
    {
        LanGuard.EnsureLocal(IPAddress.Parse("192.168.1.5"));
    }
}
