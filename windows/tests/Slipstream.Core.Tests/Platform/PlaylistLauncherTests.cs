using System.Runtime.Versioning;
using Slipstream.Core.Platform;

namespace Slipstream.Core.Tests.Platform;

[SupportedOSPlatform("windows")]
public class PlaylistLauncherTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-play-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private static PlayRequest Request() =>
        new("http://192.168.43.1:53323/media/abc123", "Holiday video", "video/mp4");

    [Fact]
    public void Writes_an_m3u_containing_the_url()
    {
        var path = new PlaylistLauncher(_dir).WritePlaylist(Request());

        Assert.EndsWith(".m3u", path);
        Assert.Contains("http://192.168.43.1:53323/media/abc123", File.ReadAllText(path));
    }

    [Fact]
    public void The_playlist_carries_the_title_as_extinf()
    {
        var content = File.ReadAllText(new PlaylistLauncher(_dir).WritePlaylist(Request()));

        Assert.StartsWith("#EXTM3U", content);
        Assert.Contains("#EXTINF:-1,Holiday video", content);
    }

    [Fact]
    public void Each_playlist_gets_a_distinct_filename()
    {
        var launcher = new PlaylistLauncher(_dir);

        Assert.NotEqual(launcher.WritePlaylist(Request()), launcher.WritePlaylist(Request()));
    }

    [Fact]
    public void A_title_with_newlines_cannot_corrupt_the_playlist()
    {
        var request = Request() with { Title = "Bad\r\nhttp://evil.example/x" };
        var content = File.ReadAllText(new PlaylistLauncher(_dir).WritePlaylist(request));

        Assert.DoesNotContain("evil.example", content.Split('\n')[2]);
        Assert.Equal(3, content.Split('\n', StringSplitOptions.RemoveEmptyEntries).Length);
    }

    [Fact]
    public void Falls_back_to_a_known_player_when_no_playlist_handler_exists()
    {
        var fakePlayer = Path.Combine(_dir, "vlc.exe");
        File.WriteAllText(fakePlayer, "");

        var launcher = new PlaylistLauncher(_dir)
        {
            KnownPlayerPaths = [fakePlayer],
            HasPlaylistHandler = () => false,
        };

        Assert.Equal(LaunchStrategy.KnownPlayer, launcher.Choose());
    }

    [Fact]
    public void Prefers_the_playlist_handler_when_one_is_registered()
    {
        var launcher = new PlaylistLauncher(_dir) { HasPlaylistHandler = () => true };

        Assert.Equal(LaunchStrategy.Playlist, launcher.Choose());
    }

    [Fact]
    public void Falls_back_to_the_url_when_nothing_else_is_available()
    {
        var launcher = new PlaylistLauncher(_dir)
        {
            KnownPlayerPaths = [Path.Combine(_dir, "not-installed.exe")],
            HasPlaylistHandler = () => false,
        };

        Assert.Equal(LaunchStrategy.Url, launcher.Choose());
    }
}
