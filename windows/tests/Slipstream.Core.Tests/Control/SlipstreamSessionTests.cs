using System.Net;
using System.Runtime.Versioning;
using Slipstream.Core.Control;
using Slipstream.Core.Control.Handlers;
using Slipstream.Core.Files;
using Slipstream.Core.Identity;
using Slipstream.Core.Media;
using Slipstream.Core.Platform;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Control;

[SupportedOSPlatform("windows")]
public class SlipstreamSessionTests : IAsyncLifetime
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-session-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(30));
    private readonly TokenVault _vault = new();

    private MediaServer _media = null!;
    private DeviceIdentity _identity = null!;
    private SlipstreamSession _session = null!;

    public async Task InitializeAsync()
    {
        File.WriteAllText(Path.Combine(_dir, "notes.txt"), "hello");
        File.WriteAllBytes(Path.Combine(_dir, "movie.mp4"), new byte[50_000]);

        _media = new MediaServer(_vault, IPAddress.Loopback, port: 0);
        _ = _media.RunAsync(_cts.Token);

        // Give the media server time to start listening
        await Task.Delay(100);

        _identity = DeviceIdentity.CreateNew("Test PC");

        _session = new SlipstreamSession(
            _identity, new FileBrowser(), _vault, _media,
            new ThumbnailProvider(Path.Combine(_dir, "thumbs"), _vault),
            new PlaylistLauncher(Path.Combine(_dir, "temp")) { HasPlaylistHandler = () => true },
            IPAddress.Loopback, streamCount: 4);
    }

    public async Task DisposeAsync()
    {
        await _cts.CancelAsync();
        await _media.DisposeAsync();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    [Fact]
    public async Task List_returns_the_directory_contents()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("list", "1", new ListRequest(_dir, null)), _cts.Token);

        Assert.Equal("list.ok", reply!.Type);
        Assert.Equal("1", reply.Id);

        var payload = reply.PayloadAs<ListResponse>()!;
        Assert.Equal(2, payload.Entries.Count);
        Assert.False(payload.Truncated);
    }

    [Fact]
    public async Task List_of_a_missing_folder_returns_an_error_message()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("list", "1", new ListRequest(Path.Combine(_dir, "nope"), null)), _cts.Token);

        Assert.Equal("list.error", reply!.Type);
    }

    [Fact]
    public async Task Stat_returns_metadata()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("stat", "2", new StatRequest(Path.Combine(_dir, "movie.mp4"))), _cts.Token);

        Assert.Equal("stat.ok", reply!.Type);
        Assert.Equal(50_000, reply.PayloadAs<FileEntry>()!.Size);
    }

    [Fact]
    public async Task Pull_request_issues_a_usable_bulk_token()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("pull.request", "3", new PullRequest(Path.Combine(_dir, "movie.mp4"))), _cts.Token);

        Assert.Equal("pull.ok", reply!.Type);

        var payload = reply.PayloadAs<PullResponse>()!;
        Assert.Equal(50_000, payload.Size);
        Assert.Equal(4, payload.Streams);
        Assert.Equal("movie.mp4", payload.Name);

        Assert.NotNull(_vault.ValidateBulk(Guid.Parse(payload.Token), Guid.Parse(payload.TransferId)));
    }

    [Fact]
    public async Task Pull_request_for_a_missing_file_returns_an_error()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("pull.request", "3", new PullRequest(Path.Combine(_dir, "nope.bin"))), _cts.Token);

        Assert.Equal("pull.error", reply!.Type);
    }

    [Fact]
    public async Task Stream_request_returns_a_reachable_media_url()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("stream.request", "4", new StatRequest(Path.Combine(_dir, "movie.mp4"))), _cts.Token);

        Assert.Equal("stream.ok", reply!.Type);

        var url = reply.PayloadAs<PlayMessage>()!.Url;
        Assert.StartsWith($"http://127.0.0.1:{_media.ListenEndPoint.Port}/media/", url);

        using var http = new HttpClient();
        using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, _cts.Token);
        Assert.True(response.IsSuccessStatusCode);
    }

    [Fact]
    public async Task Clipboard_stores_the_text_and_acknowledges()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("clipboard", "5", new ClipboardMessage("copied text")), _cts.Token);

        Assert.Equal("clipboard.ok", reply!.Type);
        Assert.Equal("copied text", _session.LastClipboardText);
    }

    [Fact]
    public async Task Clipboard_rejects_an_oversized_payload()
    {
        var oversized = new string('x', SlipstreamSession.ClipboardMaxBytes + 1);

        var reply = await _session.HandleAsync(
            ControlMessage.Request("clipboard", "6", new ClipboardMessage(oversized)), _cts.Token);

        Assert.Equal("clipboard.error", reply!.Type);
        Assert.Null(_session.LastClipboardText);
    }

    [Fact]
    public async Task Hello_is_answered_with_this_devices_identity()
    {
        // Plan 1's harness answered hello inline. Once the session owns the
        // connection, this case is the only thing keeping the handshake alive.
        var reply = await _session.HandleAsync(
            ControlMessage.Request("hello", "0", new HelloPayload(
                SlipstreamPorts.ProtocolVersion, "peer-device", "Test Phone", "deadbeef")),
            _cts.Token);

        Assert.Equal("hello.ok", reply!.Type);
        Assert.Equal("0", reply.Id);

        var payload = reply.PayloadAs<HelloPayload>()!;
        Assert.Equal(SlipstreamPorts.ProtocolVersion, payload.Version);
        Assert.Equal(_identity.DeviceId, payload.DeviceId);
        Assert.Equal(_identity.Fingerprint, payload.Fingerprint);
    }

    [Fact]
    public async Task Ping_is_answered_with_pong()
    {
        var reply = await _session.HandleAsync(ControlMessage.Request("ping", "7"), _cts.Token);

        Assert.Equal("pong", reply!.Type);
        Assert.Equal("7", reply.Id);
    }

    [Fact]
    public async Task An_unknown_message_type_is_ignored()
    {
        Assert.Null(await _session.HandleAsync(
            ControlMessage.Request("something.from.the.future", "8"), _cts.Token));
    }
}
