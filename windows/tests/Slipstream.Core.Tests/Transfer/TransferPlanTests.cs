using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class TransferPlanTests
{
    private const int Chunk = 1_048_576;

    [Fact]
    public void A_small_file_is_assigned_whole_to_one_stream()
    {
        var ranges = TransferPlan.Split(40_000, streamCount: 4, Chunk);

        Assert.Single(ranges);
        Assert.Equal(0, ranges[0].Start);
        Assert.Equal(40_000, ranges[0].Length);
    }

    [Fact]
    public void The_small_file_threshold_is_four_megabytes()
    {
        Assert.Single(TransferPlan.Split(TransferPlan.SmallFileThreshold - 1, 4, Chunk));
        Assert.True(TransferPlan.Split(TransferPlan.SmallFileThreshold + 1, 4, Chunk).Count > 1);
    }

    [Fact]
    public void A_large_file_splits_across_the_requested_streams()
    {
        var ranges = TransferPlan.Split(40 * Chunk, streamCount: 4, Chunk);

        Assert.Equal(4, ranges.Count);
        Assert.All(ranges, r => Assert.Equal(10 * Chunk, r.Length));
    }

    [Fact]
    public void Ranges_are_contiguous_and_cover_the_whole_file()
    {
        const long size = 37 * Chunk + 12_345;
        var ranges = TransferPlan.Split(size, streamCount: 4, Chunk);

        Assert.Equal(0, ranges[0].Start);
        Assert.Equal(size, ranges[^1].EndExclusive);

        for (var i = 1; i < ranges.Count; i++)
            Assert.Equal(ranges[i - 1].EndExclusive, ranges[i].Start);
    }

    [Fact]
    public void Every_range_except_the_last_starts_on_a_chunk_boundary()
    {
        var ranges = TransferPlan.Split(37 * Chunk + 999, streamCount: 4, Chunk);

        Assert.All(ranges, r => Assert.Equal(0, r.Start % Chunk));
    }

    [Fact]
    public void Remainder_chunks_go_to_the_earliest_ranges()
    {
        // 10 chunks over 4 streams: 3,3,2,2 — never 2,2,2,4.
        var ranges = TransferPlan.Split(10 * Chunk, streamCount: 4, Chunk);
        var chunkCounts = ranges.Select(r => r.Length / Chunk).ToList();

        Assert.Equal([3, 3, 2, 2], chunkCounts);
    }

    [Fact]
    public void Stream_count_is_capped_by_chunk_count()
    {
        // 5 MB is 5 chunks; 8 streams cannot each get one.
        var ranges = TransferPlan.Split(5 * Chunk, streamCount: 8, Chunk);
        Assert.Equal(5, ranges.Count);
    }

    [Fact]
    public void An_empty_file_produces_no_ranges()
    {
        Assert.Empty(TransferPlan.Split(0, 4, Chunk));
    }

    [Fact]
    public void SplitMissing_only_covers_the_gaps()
    {
        var bitmap = new ChunkBitmap(10);
        for (var i = 0; i < 6; i++) bitmap[i] = true; // first 6 chunks done

        var ranges = TransferPlan.SplitMissing(bitmap, 10 * Chunk, streamCount: 4, Chunk);

        Assert.Equal(6L * Chunk, ranges.Min(r => r.Start));
        Assert.Equal(4L * Chunk, ranges.Sum(r => r.Length));
    }

    [Fact]
    public void SplitMissing_handles_fragmented_gaps()
    {
        var bitmap = new ChunkBitmap(10);
        bitmap[0] = true;
        bitmap[5] = true;
        bitmap[9] = true;

        var ranges = TransferPlan.SplitMissing(bitmap, 10 * Chunk, streamCount: 4, Chunk);

        Assert.Equal(7L * Chunk, ranges.Sum(r => r.Length));
        Assert.All(ranges, r => Assert.Equal(0, r.Start % Chunk));
    }

    [Fact]
    public void SplitMissing_returns_nothing_for_a_complete_bitmap()
    {
        var bitmap = new ChunkBitmap(4);
        for (var i = 0; i < 4; i++) bitmap[i] = true;

        Assert.Empty(TransferPlan.SplitMissing(bitmap, 4 * Chunk, 4, Chunk));
    }

    [Fact]
    public void SplitMissing_clamps_the_final_range_to_the_file_size()
    {
        const long size = 3 * Chunk + 500;
        var bitmap = new ChunkBitmap(ChunkBitmap.ChunkCountFor(size, Chunk));

        var ranges = TransferPlan.SplitMissing(bitmap, size, 4, Chunk);

        Assert.Equal(size, ranges.Sum(r => r.Length));
        Assert.Equal(size, ranges.Max(r => r.EndExclusive));
    }

    [Fact]
    public void SplitMissing_assigns_a_small_file_whole()
    {
        // The threshold rule must hold on the path production actually calls.
        var bitmap = new ChunkBitmap(ChunkBitmap.ChunkCountFor(3 * Chunk, Chunk));

        var ranges = TransferPlan.SplitMissing(bitmap, 3 * Chunk, streamCount: 4, Chunk);

        Assert.Single(ranges);
        Assert.Equal(0, ranges[0].Start);
        Assert.Equal(3 * Chunk, ranges[0].Length);
    }

    [Fact]
    public void SplitMissing_still_subdivides_a_large_file()
    {
        var bitmap = new ChunkBitmap(ChunkBitmap.ChunkCountFor(40 * Chunk, Chunk));

        Assert.Equal(4, TransferPlan.SplitMissing(bitmap, 40 * Chunk, streamCount: 4, Chunk).Count);
    }

    [Fact]
    public void SplitMissing_does_not_apply_the_small_file_rule_to_a_partial_resume()
    {
        // A 3 MB file already half-done is still one gap — but the rule is about the
        // file, not the gap, so it stays whole either way.
        var bitmap = new ChunkBitmap(3);
        bitmap[0] = true;

        var ranges = TransferPlan.SplitMissing(bitmap, 3 * Chunk, streamCount: 4, Chunk);

        Assert.Single(ranges);
        Assert.Equal(Chunk, ranges[0].Start);
    }
}
