using System.Net;
using System.Security.Cryptography;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class FragmentedResumeTests : IAsyncLifetime
{
    private const int Chunk = 4096;

    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-frag-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(60));
    private readonly TokenVault _vault = new();

    private BulkServer _server = null!;
    private string _sourcePath = null!;
    private byte[] _sourceData = null!;

    public Task InitializeAsync()
    {
        _sourceData = RandomNumberGenerator.GetBytes(20 * Chunk);
        _sourcePath = Path.Combine(_dir, "source.bin");
        File.WriteAllBytes(_sourcePath, _sourceData);

        _server = new BulkServer(_vault, IPAddress.Loopback, port: 0);
        _ = _server.RunAsync(_cts.Token);

        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        await _cts.CancelAsync();
        await _server.DisposeAsync();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    private string Destination => Path.Combine(_dir, "downloaded.bin");

    /// <summary>Completes a scattered subset of chunks, leaving many separate gaps.</summary>
    private async Task SeedFragmentedAsync(Guid transferId, params int[] completed)
    {
        await using var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk);

        foreach (var index in completed)
        {
            var slice = _sourceData.AsMemory(index * Chunk, Chunk);
            await part.WriteChunkAsync(index, slice, Crc32C.Compute(slice.Span), _cts.Token);
        }
    }

    [Fact]
    public async Task Resumes_a_bitmap_with_more_gaps_than_streams()
    {
        var transferId = Guid.NewGuid();

        // Completed chunks scattered so MissingRanges yields 6 separate gaps,
        // against a stream budget of 4. This is the shape a dropped 4-stream
        // transfer actually leaves behind.
        await SeedFragmentedAsync(transferId, 0, 3, 6, 9, 12, 15);

        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            Assert.True(part.Bitmap.MissingRanges().Count() > 4, "test must exercise more gaps than streams");

            await new BulkClient().DownloadAsync(
                _server.ListenEndPoint, transferId, token.Value, part, 4, null, _cts.Token);

            Assert.True(await part.CompleteAsync(_cts.Token));
        }

        Assert.Equal(_sourceData, await File.ReadAllBytesAsync(Destination, _cts.Token));
    }

    [Fact]
    public async Task Never_opens_more_concurrent_sockets_than_the_stream_budget()
    {
        var transferId = Guid.NewGuid();
        await SeedFragmentedAsync(transferId, 0, 3, 6, 9, 12, 15);

        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 2);

        await using var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk);

        await new BulkClient().DownloadAsync(
            _server.ListenEndPoint, transferId, token.Value, part, streamCount: 2, null, _cts.Token);

        Assert.True(part.Bitmap.IsComplete);
    }

    [Fact]
    public async Task A_single_gap_still_works()
    {
        var transferId = Guid.NewGuid();
        await SeedFragmentedAsync(transferId, 0, 1, 2, 3, 4);

        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        await using var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk);

        await new BulkClient().DownloadAsync(
            _server.ListenEndPoint, transferId, token.Value, part, 4, null, _cts.Token);

        Assert.True(part.Bitmap.IsComplete);
    }
}
