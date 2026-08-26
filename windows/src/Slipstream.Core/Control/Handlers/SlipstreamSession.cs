using System.Net;
using System.Runtime.Versioning;
using System.Text;
using Slipstream.Core.Files;
using Slipstream.Core.Identity;
using Slipstream.Core.Media;
using Slipstream.Core.Platform;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Control.Handlers;

public sealed record ListRequest(string Path, string? Sort);
public sealed record ListResponse(string Path, IReadOnlyList<FileEntry> Entries, bool Truncated);
public sealed record StatRequest(string Path);
public sealed record PullRequest(string Path);
public sealed record PullResponse(string TransferId, string Token, long Size, int ChunkSize, int Streams, string Name);
public sealed record ErrorResponse(string Message);

/// <summary>
/// Spec §6, §8, §10. Dispatches control messages for one connected peer.
/// Unknown types return null and are ignored, never fatal.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class SlipstreamSession(
    DeviceIdentity identity,
    FileBrowser browser,
    TokenVault vault,
    MediaServer media,
    ThumbnailProvider thumbnails,
    PlaylistLauncher launcher,
    IPAddress advertisedAddress,
    int streamCount)
{
    public const int ClipboardMaxBytes = 65_536;
    public const int ChunkSize = 1_048_576;

    public string? LastClipboardText { get; private set; }

    /// <summary>
    /// Raised when the peer sends clipboard text. Slipstream.Core cannot reach the system
    /// clipboard from a plain net9.0 target — the host app (WinUI, or the harness)
    /// subscribes and completes spec §10. Storing to LastClipboardText alone reported
    /// success while doing nothing observable.
    /// </summary>
    public event Action<string>? ClipboardReceived;

    public Task<ControlMessage?> HandleAsync(ControlMessage message, CancellationToken cancellationToken) =>
        Task.FromResult(Dispatch(message));

    private ControlMessage? Dispatch(ControlMessage message) => message.Type switch
    {
        "hello" => HandleHello(message),
        "ping" => ControlMessage.Response("pong", message.Id!),
        "list" => HandleList(message),
        "stat" => HandleStat(message),
        "pull.request" => HandlePull(message),
        "stream.request" => HandleStream(message),
        "play" => HandlePlay(message),
        "clipboard" => HandleClipboard(message),
        _ => null, // A peer on a newer protocol version degrades, it does not break.
    };

    /// <summary>
    /// Version negotiation and device info. `HelloPayload` is declared in
    /// `ControlServer.cs` (Plan 1) — do not redeclare it here.
    /// </summary>
    private ControlMessage HandleHello(ControlMessage message) =>
        ControlMessage.Response("hello.ok", message.Id ?? "0", new HelloPayload(
            SlipstreamPorts.ProtocolVersion,
            identity.DeviceId,
            identity.DisplayName,
            identity.Fingerprint));

    private ControlMessage HandleList(ControlMessage message)
    {
        var request = message.PayloadAs<ListRequest>();
        if (request is null) return Error(message, "list.error", "Missing request payload.");

        // "/" is the protocol's platform-neutral root. Android has a real filesystem root,
        // Windows does not — it has drives — so a peer asking for "/" gets the drive list.
        // Without this an Android client, whose browser quite reasonably starts at "/",
        // asks Windows for a path that means nothing there and is told the folder is gone.
        if (string.IsNullOrWhiteSpace(request.Path) || request.Path is "/" or "\\")
        {
            return ControlMessage.Response("list.ok", message.Id!,
                new ListResponse("/", browser.Roots(), Truncated: false));
        }

        try
        {
            var result = browser.List(request.Path, request.Sort ?? "name");

            // Thumbnail tokens, never inline image data — listings stay small.
            var entries = result.Entries
                .Select(e => e.IsDirectory
                    ? e
                    : e with { ThumbnailToken = thumbnails.TokenFor(e.Path)?.ToString("N") })
                .ToList();

            return ControlMessage.Response("list.ok", message.Id!,
                new ListResponse(result.Path, entries, result.Truncated));
        }
        catch (DirectoryNotFoundException)
        {
            return Error(message, "list.error", "That folder is no longer there.");
        }
        catch (UnauthorizedAccessException)
        {
            return Error(message, "list.error", "Slipstream cannot read that folder.");
        }
    }

    private ControlMessage HandleStat(ControlMessage message)
    {
        var request = message.PayloadAs<StatRequest>();
        if (request is null) return Error(message, "stat.error", "Missing request payload.");

        var entry = browser.Stat(request.Path);

        return entry is null
            ? Error(message, "stat.error", "That file is no longer there.")
            : ControlMessage.Response("stat.ok", message.Id!, entry);
    }

    private ControlMessage HandlePull(ControlMessage message)
    {
        var request = message.PayloadAs<PullRequest>();
        if (request is null) return Error(message, "pull.error", "Missing request payload.");
        if (!File.Exists(request.Path)) return Error(message, "pull.error", "That file is no longer there.");

        var info = new FileInfo(request.Path);
        var transferId = Guid.NewGuid();
        var streams = Math.Clamp(streamCount, 1, 8);

        var token = vault.IssueBulk(transferId, info.FullName, info.Length, streams);

        return ControlMessage.Response("pull.ok", message.Id!, new PullResponse(
            transferId.ToString("N"), token.Value.ToString("N"),
            info.Length, ChunkSize, streams, info.Name));
    }

    private ControlMessage HandleStream(ControlMessage message)
    {
        var request = message.PayloadAs<StatRequest>();
        if (request is null) return Error(message, "stream.error", "Missing request payload.");
        if (!File.Exists(request.Path)) return Error(message, "stream.error", "That file is no longer there.");

        var info = new FileInfo(request.Path);
        var token = vault.IssueMedia(info.FullName, info.Length);

        return ControlMessage.Response("stream.ok", message.Id!, new PlayMessage(
            media.UrlFor(token, advertisedAddress), info.Name, browser.Stat(info.FullName)?.Mime));
    }

    private ControlMessage? HandlePlay(ControlMessage message)
    {
        var request = message.PayloadAs<PlayMessage>();
        if (request is null) return null;

        // Fire and forget, per spec §8 — no remote control, no position sync.
        launcher.Launch(new PlayRequest(request.Url, request.Title, request.Mime));

        return message.Id is null ? null : ControlMessage.Response("play.ok", message.Id);
    }

    private ControlMessage HandleClipboard(ControlMessage message)
    {
        var request = message.PayloadAs<ClipboardMessage>();
        if (request is null) return Error(message, "clipboard.error", "Missing request payload.");

        if (Encoding.UTF8.GetByteCount(request.Text) > ClipboardMaxBytes)
            return Error(message, "clipboard.error", "That text is too large to send.");

        LastClipboardText = request.Text;
        ClipboardReceived?.Invoke(request.Text);

        return ControlMessage.Response("clipboard.ok", message.Id!);
    }

    private static ControlMessage Error(ControlMessage request, string type, string message) =>
        ControlMessage.Response(type, request.Id ?? "0", new ErrorResponse(message));
}

public sealed record PlayMessage(string Url, string Title, string? Mime);
public sealed record ClipboardMessage(string Text);
