using System.Net;
using System.Security.Cryptography;
using Slipstream.Core.Net;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class BulkClientTests : IAsyncLifetime
{
    private const int Chunk = 4096;

    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-bulkclient-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(60));
    private readonly TokenVault _vault = new();

    private BulkServer _server = null!;
    private string _sourcePath = null!;
    private byte[] _sourceData = null!;

    public Task InitializeAsync()
    {
        _sourceData = RandomNumberGenerator.GetBytes(20 * Chunk + 321);
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

    [Fact]
    public async Task Downloads_a_file_byte_identically()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            await new BulkClient().DownloadAsync(
                _server.ListenEndPoint, transferId, token.Value, part, 4, null, _cts.Token);

            Assert.True(await part.CompleteAsync(_cts.Token));
        }

        Assert.Equal(_sourceData, await File.ReadAllBytesAsync(Destination));
    }

    [Fact]
    public async Task A_single_stream_download_also_works()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 1);

        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            await new BulkClient().DownloadAsync(
                _server.ListenEndPoint, transferId, token.Value, part, 1, null, _cts.Token);

            Assert.True(await part.CompleteAsync(_cts.Token));
        }

        Assert.Equal(_sourceData, await File.ReadAllBytesAsync(Destination));
    }

    [Fact]
    public async Task Reports_progress_that_reaches_the_total()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        var reports = new List<TransferProgress>();
        var progress = new Progress<TransferProgress>(p => { lock (reports) reports.Add(p); });

        await using var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk);
        await new BulkClient().DownloadAsync(
            _server.ListenEndPoint, transferId, token.Value, part, 4, progress, _cts.Token);

        await Task.Delay(200, _cts.Token); // Progress<T> posts asynchronously

        lock (reports)
        {
            Assert.NotEmpty(reports);
            Assert.Equal(_sourceData.Length, reports.Max(r => r.BytesCompleted));
            Assert.All(reports, r => Assert.Equal(_sourceData.Length, r.TotalBytes));
        }
    }

    [Fact]
    public async Task Resumes_from_a_partial_download()
    {
        var transferId = Guid.NewGuid();

        // First pass: complete only chunks 0..4 by hand.
        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            for (var i = 0; i < 5; i++)
            {
                var slice = _sourceData.AsMemory(i * Chunk, Chunk);
                await part.WriteChunkAsync(i, slice, Crc32C.Compute(slice.Span), _cts.Token);
            }
        }

        // Second pass: resume.
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            Assert.Equal(5, part.Bitmap.CompletedCount);

            await new BulkClient().DownloadAsync(
                _server.ListenEndPoint, transferId, token.Value, part, 4, null, _cts.Token);

            Assert.True(await part.CompleteAsync(_cts.Token));
        }

        Assert.Equal(_sourceData, await File.ReadAllBytesAsync(Destination));
    }

    [Fact]
    public async Task Survives_an_interruption_and_completes_on_retry()
    {
        var transferId = Guid.NewGuid();

        // Interrupt aggressively part-way through.
        using (var interrupt = new CancellationTokenSource(TimeSpan.FromMilliseconds(30)))
        {
            var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);
            await using var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk);

            try
            {
                await new BulkClient().DownloadAsync(
                    _server.ListenEndPoint, transferId, token.Value, part, 4, null, interrupt.Token);
            }
            catch (OperationCanceledException) { }
        }

        // Retry with a fresh token, as the engine would after re-discovery.
        var retryToken = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            await new BulkClient().DownloadAsync(
                _server.ListenEndPoint, transferId, retryToken.Value, part, 4, null, _cts.Token);

            Assert.True(await part.CompleteAsync(_cts.Token));
        }

        Assert.Equal(_sourceData, await File.ReadAllBytesAsync(Destination));
    }

    [Fact]
    public async Task Refuses_a_non_local_endpoint()
    {
        var transferId = Guid.NewGuid();
        await using var part = PartFile.OpenOrCreate(Destination, transferId, 100, Chunk);

        await Assert.ThrowsAsync<NonLocalAddressException>(() =>
            new BulkClient().DownloadAsync(
                new IPEndPoint(IPAddress.Parse("8.8.8.8"), 53322),
                transferId, Guid.NewGuid(), part, 4, null, _cts.Token));
    }
}
