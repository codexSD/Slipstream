using System.Net;
using System.Runtime.Versioning;
using Slipstream.Core;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Net;

/// <summary>
/// <see cref="SlipstreamPeer"/> used to hard-construct <c>new NetworkInfo()</c>, so neither a
/// test nor a caller could supply or override the network it binds to.
/// </summary>
[SupportedOSPlatform("windows")]
public class NetworkInfoInjectionTests
{
    private sealed class StubNetworkInfo(LocalNetwork? network) : INetworkInfo
    {
        public LocalNetwork? Current() => network;
    }

    [Fact]
    public async Task SlipstreamPeer_uses_an_injected_network_info()
    {
        var directory = Path.Combine(Path.GetTempPath(), "slipstream-tests", Guid.NewGuid().ToString("N"));
        var expected = new LocalNetwork(
            IPAddress.Parse("10.199.176.38"),
            IPAddress.Parse("10.199.176.137"),
            24,
            "test-key");

        await using var peer = new SlipstreamPeer(directory, "test", new StubNetworkInfo(expected));

        Assert.Same(expected, peer.Network);

        Directory.Delete(directory, recursive: true);
    }

    [Fact]
    public async Task SlipstreamPeer_defaults_to_the_live_network_info()
    {
        var directory = Path.Combine(Path.GetTempPath(), "slipstream-tests", Guid.NewGuid().ToString("N"));

        await using var peer = new SlipstreamPeer(directory, "test");

        // Same answer the live implementation gives — including null on a networkless runner.
        Assert.Equal(new NetworkInfo().Current()?.Key, peer.Network?.Key);

        Directory.Delete(directory, recursive: true);
    }
}
