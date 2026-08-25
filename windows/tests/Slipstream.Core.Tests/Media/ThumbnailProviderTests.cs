using System.Runtime.Versioning;
using Slipstream.Core.Media;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Media;

[SupportedOSPlatform("windows")]
public class ThumbnailProviderTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-thumb-").FullName;
    private readonly string _cache;
    private readonly ThumbnailProvider _provider;

    public ThumbnailProviderTests()
    {
        _cache = Path.Combine(_dir, "cache");
        _provider = new ThumbnailProvider(_cache, new TokenVault());
    }

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    /// <summary>A 2x2 PNG — the shell reliably produces a thumbnail for a real image.</summary>
    private string MakeImage(string name = "pic.png")
    {
        var path = Path.Combine(_dir, name);
        File.WriteAllBytes(path, Convert.FromBase64String(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEklEQVR4nGP8z" +
            "8Dwn4GBgYERRAAAGgcCAaqqZaEAAAAASUVORK5CYII="));
        return path;
    }

    [Fact]
    public void Generates_a_thumbnail_for_an_image()
    {
        if (!OperatingSystem.IsWindows()) return;

        var thumbnail = _provider.Generate(MakeImage());

        Assert.NotNull(thumbnail);
        Assert.True(File.Exists(thumbnail));
        Assert.True(new FileInfo(thumbnail).Length > 0);
    }

    [Fact]
    public void Caches_by_path_size_and_mtime()
    {
        if (!OperatingSystem.IsWindows()) return;

        var path = MakeImage();

        var first = _provider.Generate(path);
        var firstWritten = File.GetLastWriteTimeUtc(first!);

        var second = _provider.Generate(path);

        Assert.Equal(first, second);
        Assert.Equal(firstWritten, File.GetLastWriteTimeUtc(second!)); // not regenerated
    }

    [Fact]
    public void A_modified_file_gets_a_new_cache_entry()
    {
        if (!OperatingSystem.IsWindows()) return;

        var path = MakeImage();
        var first = _provider.Generate(path);

        File.AppendAllText(path, "changed");
        var second = _provider.Generate(path);

        Assert.NotEqual(first, second);
    }

    [Fact]
    public void Returns_null_for_a_zero_byte_file_of_an_unknown_type()
    {
        var path = Path.Combine(_dir, "empty.slipstream-unknown");
        File.WriteAllBytes(path, []);

        // No registered handler and no content to render: the shell has nothing to give.
        Assert.Null(_provider.Generate(path));
    }

    [Fact]
    public void Returns_null_for_a_missing_file()
    {
        Assert.Null(_provider.Generate(Path.Combine(_dir, "nope.png")));
    }

    [Fact]
    public void TokenFor_then_Resolve_round_trips()
    {
        if (!OperatingSystem.IsWindows()) return;

        var token = _provider.TokenFor(MakeImage());

        Assert.NotNull(token);
        Assert.True(File.Exists(_provider.Resolve(token.Value)));
    }

    [Fact]
    public void Resolve_returns_null_for_an_unknown_token()
    {
        Assert.Null(_provider.Resolve(Guid.NewGuid()));
    }
}
