using System.Net;
using Slipstream.Core.Discovery;

namespace Slipstream.Core.Tests.Discovery;

public class EndpointCacheTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-cache-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    [Fact]
    public void Get_returns_null_for_an_unknown_network()
    {
        Assert.Null(new EndpointCache(_dir).Get("unknown-ssid"));
    }

    [Fact]
    public void Set_then_Get_round_trips_and_persists()
    {
        var endpoint = new IPEndPoint(IPAddress.Parse("192.168.43.1"), 53321);
        new EndpointCache(_dir).Set("home-wifi", endpoint);

        Assert.Equal(endpoint, new EndpointCache(_dir).Get("home-wifi"));
    }

    [Fact]
    public void Entries_are_keyed_per_network()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("hotspot", new IPEndPoint(IPAddress.Parse("192.168.43.1"), 53321));
        cache.Set("cafe", new IPEndPoint(IPAddress.Parse("10.0.0.7"), 53321));

        Assert.Equal(IPAddress.Parse("192.168.43.1"), cache.Get("hotspot")!.Address);
        Assert.Equal(IPAddress.Parse("10.0.0.7"), cache.Get("cafe")!.Address);
    }

    [Fact]
    public void Set_overwrites_an_existing_entry()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("wifi", new IPEndPoint(IPAddress.Parse("10.0.0.1"), 53321));
        cache.Set("wifi", new IPEndPoint(IPAddress.Parse("10.0.0.2"), 53321));

        Assert.Equal(IPAddress.Parse("10.0.0.2"), cache.Get("wifi")!.Address);
    }

    [Fact]
    public void Clear_empties_the_cache()
    {
        var cache = new EndpointCache(_dir);
        cache.Set("wifi", new IPEndPoint(IPAddress.Loopback, 53321));
        cache.Clear();

        Assert.Null(cache.Get("wifi"));
        Assert.Null(new EndpointCache(_dir).Get("wifi"));
    }
}
