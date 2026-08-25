using System.Text.Json;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class BulkFrameHeaderTests
{
    private static BulkFrameHeader Sample() => new(
        Version: 1,
        StreamIndex: 2,
        Token: new Guid("ffeeddcc-bbaa-9988-7766-554433221100"),
        TransferId: new Guid("0f0e0d0c-0b0a-0908-0706-050403020100"),
        RangeStart: 2 * 1024 * 1024,
        RangeLength: 3 * 1024 * 1024,
        ChunkSize: 1024 * 1024);

    [Fact]
    public void Occupies_exactly_64_bytes()
    {
        Assert.Equal(64, BulkFrameHeader.Size);

        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        Sample().WriteTo(buffer);
    }

    [Fact]
    public void Round_trips()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        Sample().WriteTo(buffer);

        Assert.True(BulkFrameHeader.TryRead(buffer, out var parsed));
        Assert.Equal(Sample(), parsed);
    }

    [Fact]
    public void Starts_with_the_SLPS_magic()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        Sample().WriteTo(buffer);

        Assert.Equal((byte)'S', buffer[0]);
        Assert.Equal((byte)'L', buffer[1]);
        Assert.Equal((byte)'P', buffer[2]);
        Assert.Equal((byte)'S', buffer[3]);
    }

    [Fact]
    public void Encodes_integers_big_endian()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        Sample().WriteTo(buffer);

        // version = 1 at offset 4
        Assert.Equal(0x00, buffer[4]);
        Assert.Equal(0x01, buffer[5]);
        // rangeStart = 2097152 = 0x200000 at offset 40, 8 bytes big-endian
        Assert.Equal(0x00, buffer[40]);
        Assert.Equal(0x20, buffer[45]);
        Assert.Equal(0x00, buffer[47]);
    }

    [Fact]
    public void Reserved_bytes_are_zero()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        buffer.Fill(0xAB);
        Sample().WriteTo(buffer);

        Assert.Equal(0, buffer[60]);
        Assert.Equal(0, buffer[63]);
    }

    [Fact]
    public void TryRead_rejects_bad_magic()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        Sample().WriteTo(buffer);
        buffer[0] = (byte)'X';

        Assert.False(BulkFrameHeader.TryRead(buffer, out _));
    }

    [Fact]
    public void TryRead_rejects_a_future_version()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        (Sample() with { Version = 99 }).WriteTo(buffer);

        Assert.False(BulkFrameHeader.TryRead(buffer, out _));
    }

    [Fact]
    public void TryRead_rejects_a_short_buffer()
    {
        Assert.False(BulkFrameHeader.TryRead(new byte[63], out _));
    }

    [Theory]
    [InlineData(-1L, 100L, 1024)]
    [InlineData(0L, -5L, 1024)]
    [InlineData(0L, 100L, 0)]
    public void TryRead_rejects_nonsensical_ranges(long start, long length, int chunkSize)
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        (Sample() with { RangeStart = start, RangeLength = length, ChunkSize = chunkSize }).WriteTo(buffer);

        Assert.False(BulkFrameHeader.TryRead(buffer, out _));
    }

    [Fact]
    public void Matches_the_shared_conformance_vectors()
    {
        var path = Path.Combine(VectorPaths.Root, "bulk-headers.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));

        foreach (var testCase in doc.RootElement.GetProperty("cases").EnumerateArray())
        {
            var fields = testCase.GetProperty("fields");
            var expectedHex = testCase.GetProperty("bytes").GetString()!.Replace("_", "");

            Assert.NotEqual("PENDING", expectedHex);

            var header = new BulkFrameHeader(
                (ushort)fields.GetProperty("version").GetInt32(),
                (ushort)fields.GetProperty("streamIndex").GetInt32(),
                GuidFromHex(fields.GetProperty("token").GetString()!),
                GuidFromHex(fields.GetProperty("transferId").GetString()!),
                fields.GetProperty("rangeStart").GetInt64(),
                fields.GetProperty("rangeLength").GetInt64(),
                fields.GetProperty("chunkSize").GetInt32());

            var buffer = new byte[BulkFrameHeader.Size];
            header.WriteTo(buffer);

            Assert.Equal(expectedHex, Convert.ToHexString(buffer).ToLowerInvariant());
        }
    }

    private static Guid GuidFromHex(string hex) => new(Convert.FromHexString(hex), bigEndian: true);
}
