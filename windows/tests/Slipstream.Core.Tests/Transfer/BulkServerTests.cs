using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class BulkServerTests : IAsyncLifetime
{
    private const int Chunk = 4096;

    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-bulk-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(30));
    private readonly TokenVault _vault = new();

    private BulkServer _server = null!;
    private string _sourcePath = null!;
    private byte[] _sourceData = null!;

    public Task InitializeAsync()
    {
        _sourceData = RandomNumberGenerator.GetBytes(10 * Chunk + 777);
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

    private async Task<NetworkStream> ConnectAsync()
    {
        var tcp = new TcpClient();
        await tcp.ConnectAsync(_server.ListenEndPoint, _cts.Token);
        return tcp.GetStream();
    }

    private async Task SendHeaderAsync(Stream stream, BulkFrameHeader header)
    {
        var buffer = new byte[BulkFrameHeader.Size];
        header.WriteTo(buffer);
        await stream.WriteAsync(buffer, _cts.Token);
        await stream.FlushAsync(_cts.Token);
    }

    /// <summary>Reads [len][data][crc] chunks until the range is consumed.</summary>
    private async Task<byte[]> ReadRangeAsync(Stream stream, long rangeLength)
    {
        var output = new MemoryStream();
        var lengthBuffer = new byte[4];

        while (output.Length < rangeLength)
        {
            await stream.ReadExactlyAsync(lengthBuffer, _cts.Token);
            var chunkLength = BinaryPrimitives.ReadInt32BigEndian(lengthBuffer);

            var data = new byte[chunkLength];
            await stream.ReadExactlyAsync(data, _cts.Token);

            await stream.ReadExactlyAsync(lengthBuffer, _cts.Token);
            var crc = BinaryPrimitives.ReadUInt32BigEndian(lengthBuffer);

            Assert.Equal(Crc32C.Compute(data), crc);

            output.Write(data);
        }

        return output.ToArray();
    }

    [Fact]
    public async Task Serves_a_whole_file_range()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 1);

        await using var stream = await ConnectAsync();
        await SendHeaderAsync(stream, new BulkFrameHeader(1, 0, token.Value, transferId, 0, _sourceData.Length, Chunk));

        Assert.Equal(_sourceData, await ReadRangeAsync(stream, _sourceData.Length));
    }

    [Fact]
    public async Task Serves_a_partial_range_from_the_correct_offset()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 1);

        const long start = 3 * Chunk;
        const long length = 2 * Chunk;

        await using var stream = await ConnectAsync();
        await SendHeaderAsync(stream, new BulkFrameHeader(1, 1, token.Value, transferId, start, length, Chunk));

        Assert.Equal(_sourceData[(int)start..(int)(start + length)], await ReadRangeAsync(stream, length));
    }

    [Fact]
    public async Task Serves_a_short_final_chunk()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 1);

        const long start = 10 * Chunk;
        var length = _sourceData.Length - start;

        await using var stream = await ConnectAsync();
        await SendHeaderAsync(stream, new BulkFrameHeader(1, 0, token.Value, transferId, start, length, Chunk));

        var received = await ReadRangeAsync(stream, length);

        Assert.Equal(777, received.Length);
        Assert.Equal(_sourceData[(int)start..], received);
    }

    [Fact]
    public async Task Closes_the_socket_for_an_invalid_token()
    {
        await using var stream = await ConnectAsync();
        await SendHeaderAsync(stream, new BulkFrameHeader(1, 0, Guid.NewGuid(), Guid.NewGuid(), 0, 100, Chunk));

        var buffer = new byte[1];
        Assert.Equal(0, await stream.ReadAsync(buffer, _cts.Token)); // EOF, no error frame
    }

    [Fact]
    public async Task Closes_the_socket_for_a_bad_magic()
    {
        await using var stream = await ConnectAsync();

        await stream.WriteAsync(new byte[BulkFrameHeader.Size], _cts.Token);
        await stream.FlushAsync(_cts.Token);

        var buffer = new byte[1];
        Assert.Equal(0, await stream.ReadAsync(buffer, _cts.Token));
    }

    [Fact]
    public async Task Serves_multiple_concurrent_streams_from_one_token()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        var ranges = TransferPlan.Split(_sourceData.Length, 4, Chunk);

        var results = await Task.WhenAll(ranges.Select(async (range, index) =>
        {
            await using var stream = await ConnectAsync();
            await SendHeaderAsync(stream, new BulkFrameHeader(
                1, (ushort)index, token.Value, transferId, range.Start, range.Length, Chunk));

            return (range, data: await ReadRangeAsync(stream, range.Length));
        }));

        var reassembled = new byte[_sourceData.Length];
        foreach (var (range, data) in results)
            data.CopyTo(reassembled, (int)range.Start);

        Assert.Equal(_sourceData, reassembled);
    }
}
