namespace Slipstream.Core.Transfer;

public readonly record struct ByteRange(long Start, long Length)
{
    public long EndExclusive => Start + Length;
}

/// <summary>
/// Spec §7 parallelism. Ranges always start on a chunk boundary so the receiver can
/// derive chunk indices without transmitting them.
/// </summary>
public static class TransferPlan
{
    public const int SmallFileThreshold = 4 * 1024 * 1024;

    public static IReadOnlyList<ByteRange> Split(long fileSize, int streamCount, int chunkSize)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(fileSize);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(chunkSize);

        if (fileSize == 0) return [];

        // Range-splitting a small file costs more than it saves.
        if (fileSize < SmallFileThreshold) return [new ByteRange(0, fileSize)];

        var chunkCount = ChunkBitmap.ChunkCountFor(fileSize, chunkSize);
        var streams = Math.Clamp(streamCount, 1, chunkCount);

        var chunksPerStream = chunkCount / streams;
        var remainder = chunkCount % streams;

        var ranges = new List<ByteRange>(streams);
        var chunkIndex = 0;

        for (var i = 0; i < streams; i++)
        {
            // Remainder chunks go to the earliest ranges, so lengths differ by at
            // most one chunk and no single stream becomes the long pole.
            var chunks = chunksPerStream + (i < remainder ? 1 : 0);

            var start = (long)chunkIndex * chunkSize;
            var length = Math.Min((long)chunks * chunkSize, fileSize - start);

            ranges.Add(new ByteRange(start, length));
            chunkIndex += chunks;
        }

        return ranges;
    }

    /// <summary>Ranges covering only the chunks the bitmap reports as missing.</summary>
    public static IReadOnlyList<ByteRange> SplitMissing(
        ChunkBitmap bitmap, long fileSize, int streamCount, int chunkSize)
    {
        var ranges = new List<ByteRange>();

        foreach (var gap in bitmap.MissingRanges())
        {
            var start = (long)gap.Start.Value * chunkSize;
            var end = Math.Min((long)gap.End.Value * chunkSize, fileSize);

            if (end <= start) continue;

            ranges.Add(new ByteRange(start, end - start));
        }

        if (ranges.Count == 0) return [];

        // Subdivide the largest gaps so all available streams stay busy.
        var streams = Math.Max(1, streamCount);
        while (ranges.Count < streams)
        {
            var largestIndex = 0;
            for (var i = 1; i < ranges.Count; i++)
                if (ranges[i].Length > ranges[largestIndex].Length) largestIndex = i;

            var largest = ranges[largestIndex];
            var halfChunks = largest.Length / chunkSize / 2;
            if (halfChunks == 0) break;

            var splitAt = largest.Start + halfChunks * chunkSize;

            ranges[largestIndex] = new ByteRange(largest.Start, splitAt - largest.Start);
            ranges.Insert(largestIndex + 1, new ByteRange(splitAt, largest.EndExclusive - splitAt));
        }

        return ranges;
    }
}
