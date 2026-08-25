using System.Diagnostics;
using System.Runtime.Versioning;
using System.Text;
using Microsoft.Win32;

namespace Slipstream.Core.Platform;

public sealed record PlayRequest(string Url, string Title, string? Mime);

public enum LaunchStrategy
{
    Playlist,
    KnownPlayer,
    Url,
}

/// <summary>
/// Spec §8. Handing a bare http:// URL to ShellExecute opens the default *browser*,
/// not the default media player. Writing a one-line .m3u and launching that resolves
/// through the playlist handler instead, which is a media player.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class PlaylistLauncher(string tempDirectory)
{
    public IReadOnlyList<string> KnownPlayerPaths { get; init; } =
    [
        @"C:\Program Files\VideoLAN\VLC\vlc.exe",
        @"C:\Program Files (x86)\VideoLAN\VLC\vlc.exe",
        @"C:\Program Files\MPC-HC\mpc-hc64.exe",
        @"C:\Program Files\DAUM\PotPlayer\PotPlayerMini64.exe",
    ];

    /// <summary>Injectable so the strategy choice is testable without installing a player.</summary>
    public Func<bool> HasPlaylistHandler { get; init; } = DefaultHasPlaylistHandler;

    public string WritePlaylist(PlayRequest request)
    {
        Directory.CreateDirectory(tempDirectory);

        var path = Path.Combine(tempDirectory, $"slipstream-{Guid.NewGuid():N}.m3u");

        // The title arrives from the peer: strip anything that could inject a line.
        var safeTitle = request.Title.ReplaceLineEndings(" ").Trim();

        var content = new StringBuilder()
            .Append("#EXTM3U\n")
            .Append($"#EXTINF:-1,{safeTitle}\n")
            .Append(request.Url)
            .Append('\n');

        File.WriteAllText(path, content.ToString(), Encoding.UTF8);
        return path;
    }

    public LaunchStrategy Choose()
    {
        if (HasPlaylistHandler()) return LaunchStrategy.Playlist;
        if (KnownPlayerPaths.Any(File.Exists)) return LaunchStrategy.KnownPlayer;

        return LaunchStrategy.Url;
    }

    public void Launch(PlayRequest request)
    {
        switch (Choose())
        {
            case LaunchStrategy.Playlist:
                Start(WritePlaylist(request), arguments: null);
                break;

            case LaunchStrategy.KnownPlayer:
                Start(KnownPlayerPaths.First(File.Exists), $"\"{request.Url}\"");
                break;

            default:
                Start(request.Url, arguments: null);
                break;
        }
    }

    private static void Start(string target, string? arguments) =>
        Process.Start(new ProcessStartInfo(target)
        {
            Arguments = arguments ?? string.Empty,
            UseShellExecute = true,
        });

    private static bool DefaultHasPlaylistHandler()
    {
        try
        {
            using var key = Registry.ClassesRoot.OpenSubKey(".m3u");
            var handler = key?.GetValue(null) as string;

            if (string.IsNullOrWhiteSpace(handler)) return false;

            using var command = Registry.ClassesRoot.OpenSubKey($@"{handler}\shell\open\command");
            return command?.GetValue(null) is string { Length: > 0 };
        }
        catch (Exception)
        {
            return false;
        }
    }
}
