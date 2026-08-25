using System.Text.Json;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class ChunkBitmapTests
{
    [Fact]
    public void A_new_bitmap_is_empty()
    {
        var bitmap = new ChunkBitmap(10);

        Assert.Equal(10, bitmap.ChunkCount);
        Assert.Equal(0, bitmap.CompletedCount);
        Assert.False(bitmap.IsComplete);
        Assert.False(bitmap[0]);
    }

    [Fact]
    public void Setting_and_reading_a_chunk_round_trips()
    {
        var bitmap = new ChunkBitmap(10) { [3] = true };

        Assert.True(bitmap[3]);
        Assert.False(bitmap[4]);
        Assert.Equal(1, bitmap.CompletedCount);
    }

    [Fact]
    public void Setting_the_same_chunk_twice_does_not_double_count()
    {
        var bitmap = new ChunkBitmap(10) { [3] = true };
        bitmap[3] = true;

        Assert.Equal(1, bitmap.CompletedCount);
    }

    [Fact]
    public void IsComplete_when_every_chunk_is_set()
    {
        var bitmap = new ChunkBitmap(3);
        for (var i = 0; i < 3; i++) bitmap[i] = true;

        Assert.True(bitmap.IsComplete);
    }

    [Fact]
    public void MissingRanges_yields_contiguous_runs()
    {
        var bitmap = new ChunkBitmap(10);
        bitmap[0] = true;
        bitmap[1] = true;
        bitmap[5] = true;

        var missing = bitmap.MissingRanges().ToList();

        Assert.Equal(2, missing.Count);
        Assert.Equal(2, missing[0].Start.Value);
        Assert.Equal(5, missing[0].End.Value);   // exclusive: chunks 2,3,4
        Assert.Equal(6, missing[1].Start.Value);
        Assert.Equal(10, missing[1].End.Value);  // chunks 6..9
    }

    [Fact]
    public void MissingRanges_is_empty_for_a_complete_bitmap()
    {
        var bitmap = new ChunkBitmap(4);
        for (var i = 0; i < 4; i++) bitmap[i] = true;

        Assert.Empty(bitmap.MissingRanges());
    }

    [Fact]
    public void MissingRanges_covers_everything_for_an_empty_bitmap()
    {
        var missing = new ChunkBitmap(7).MissingRanges().ToList();

        Assert.Single(missing);
        Assert.Equal(0, missing[0].Start.Value);
        Assert.Equal(7, missing[0].End.Value);
    }

    [Fact]
    public void Base64_round_trips()
    {
        var bitmap = new ChunkBitmap(20);
        bitmap[0] = true;
        bitmap[7] = true;
        bitmap[19] = true;

        var restored = ChunkBitmap.FromBase64(bitmap.ToBase64(), 20);

        Assert.Equal(3, restored.CompletedCount);
        Assert.True(restored[0]);
        Assert.True(restored[7]);
        Assert.True(restored[19]);
        Assert.False(restored[8]);
    }

    [Fact]
    public void Matches_the_shared_conformance_vectors()
    {
        var path = Path.Combine(VectorPaths.Root, "chunk-bitmaps.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));

        foreach (var testCase in doc.RootElement.GetProperty("cases").EnumerateArray())
        {
            var chunkCount = testCase.GetProperty("chunkCount").GetInt32();
            var bitmap = new ChunkBitmap(chunkCount);

            foreach (var index in testCase.GetProperty("complete").EnumerateArray())
                bitmap[index.GetInt32()] = true;

            Assert.Equal(testCase.GetProperty("base64").GetString(), bitmap.ToBase64());
        }
    }

    [Theory]
    [InlineData(0L, 1_048_576, 0)]
    [InlineData(1L, 1_048_576, 1)]
    [InlineData(1_048_576L, 1_048_576, 1)]
    [InlineData(1_048_577L, 1_048_576, 2)]
    [InlineData(10_485_760L, 1_048_576, 10)]
    public void ChunkCountFor_rounds_up(long fileSize, int chunkSize, int expected)
    {
        Assert.Equal(expected, ChunkBitmap.ChunkCountFor(fileSize, chunkSize));
    }

    [Fact]
    public void Rejects_an_out_of_range_index()
    {
        var bitmap = new ChunkBitmap(4);
        Assert.Throws<ArgumentOutOfRangeException>(() => bitmap[4] = true);
        Assert.Throws<ArgumentOutOfRangeException>(() => bitmap[-1] = true);
    }
}
