using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using Slipstream.Core.Media;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Media;

public class MediaServerTests : IAsyncLifetime
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-media-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(30));
    private readonly TokenVault _vault = new();
    private readonly HttpClient _http = new();

    private MediaServer _server = null!;
    private byte[] _data = null!;
    private TransferToken _token = null!;

    public Task InitializeAsync()
    {
        _data = RandomNumberGenerator.GetBytes(100_000);
        var path = Path.Combine(_dir, "movie.mp4");
        File.WriteAllBytes(path, _data);

        _token = _vault.IssueMedia(path, _data.Length);

        _server = new MediaServer(_vault, IPAddress.Loopback, port: 0);
        _ = _server.RunAsync(_cts.Token);

        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        await _cts.CancelAsync();
        await _server.DisposeAsync();
        _http.Dispose();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    private string Url => $"http://{_server.ListenEndPoint}/media/{_token.Value:N}";

    [Fact]
    public async Task Serves_the_whole_file_with_a_200()
    {
        var response = await _http.GetAsync(Url, _cts.Token);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(_data, await response.Content.ReadAsByteArrayAsync(_cts.Token));
    }

    [Fact]
    public async Task Advertises_range_support()
    {
        var response = await _http.GetAsync(Url, _cts.Token);
        Assert.Contains("bytes", response.Headers.AcceptRanges);
    }

    [Fact]
    public async Task Serves_a_range_with_a_206_and_correct_bytes()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Url);
        request.Headers.Range = new RangeHeaderValue(1000, 1999);

        var response = await _http.SendAsync(request, _cts.Token);
        var body = await response.Content.ReadAsByteArrayAsync(_cts.Token);

        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        Assert.Equal(1000, body.Length);
        Assert.Equal(_data[1000..2000], body);
        Assert.Equal(1000, response.Content.Headers.ContentRange!.From);
        Assert.Equal(1999, response.Content.Headers.ContentRange.To);
        Assert.Equal(_data.Length, response.Content.Headers.ContentRange.Length);
    }

    [Fact]
    public async Task Serves_an_open_ended_range()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Url);
        request.Headers.Range = new RangeHeaderValue(99_000, null);

        var response = await _http.SendAsync(request, _cts.Token);

        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        Assert.Equal(1000, (await response.Content.ReadAsByteArrayAsync(_cts.Token)).Length);
    }

    [Fact]
    public async Task Reports_the_content_type_from_the_extension()
    {
        var response = await _http.GetAsync(Url, _cts.Token);
        Assert.Equal("video/mp4", response.Content.Headers.ContentType!.MediaType);
    }

    [Fact]
    public async Task Rejects_an_unknown_token_with_404()
    {
        var response = await _http.GetAsync(
            $"http://{_server.ListenEndPoint}/media/{Guid.NewGuid():N}", _cts.Token);

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task Rejects_an_unsatisfiable_range_with_416()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Url);
        request.Headers.TryAddWithoutValidation("Range", "bytes=999999-1000000");

        var response = await _http.SendAsync(request, _cts.Token);

        Assert.Equal(HttpStatusCode.RequestedRangeNotSatisfiable, response.StatusCode);
    }

    [Fact]
    public async Task Rejects_an_unknown_route_with_404()
    {
        var response = await _http.GetAsync($"http://{_server.ListenEndPoint}/etc/passwd", _cts.Token);
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public void UrlFor_builds_an_address_the_peer_can_reach()
    {
        var url = _server.UrlFor(_token, IPAddress.Parse("192.168.43.1"));

        Assert.StartsWith("http://192.168.43.1:", url);
        Assert.Contains($"/media/{_token.Value:N}", url);
    }
}
