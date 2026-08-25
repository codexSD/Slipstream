using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests.Identity;

public class PairedPeerStoreTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-peer-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private static PairedPeer Peer(string id = "abc123", string fp = "deadbeef") =>
        new(id, fp, "Test Phone", DateTimeOffset.UtcNow);

    [Fact]
    public void A_new_store_is_unpaired()
    {
        var store = new PairedPeerStore(_dir);
        Assert.False(store.IsPaired);
        Assert.Null(store.Current);
    }

    [Fact]
    public void Pair_persists_across_instances()
    {
        new PairedPeerStore(_dir).Pair(Peer());

        var reloaded = new PairedPeerStore(_dir);
        Assert.True(reloaded.IsPaired);
        Assert.Equal("abc123", reloaded.Current!.DeviceId);
        Assert.Equal("Test Phone", reloaded.Current.DisplayName);
    }

    [Fact]
    public void Pairing_again_replaces_the_existing_peer()
    {
        var store = new PairedPeerStore(_dir);
        store.Pair(Peer("first", "aaaa"));
        store.Pair(Peer("second", "bbbb"));

        Assert.Equal("second", store.Current!.DeviceId);
        Assert.False(store.Trusts("aaaa"));
        Assert.True(store.Trusts("bbbb"));
    }

    [Fact]
    public void Trusts_is_case_insensitive_and_false_when_unpaired()
    {
        var store = new PairedPeerStore(_dir);
        Assert.False(store.Trusts("deadbeef"));

        store.Pair(Peer(fp: "deadbeef"));
        Assert.True(store.Trusts("DEADBEEF"));
        Assert.False(store.Trusts("cafebabe"));
    }

    [Fact]
    public void Unpair_clears_the_store()
    {
        var store = new PairedPeerStore(_dir);
        store.Pair(Peer());
        store.Unpair();

        Assert.False(store.IsPaired);
        Assert.False(new PairedPeerStore(_dir).IsPaired);
    }
}
