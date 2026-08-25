namespace Slipstream.Core.Transfer;

/// <summary>
/// Spec §7 resume. One bit per chunk, set = complete and CRC-verified.
/// Little-endian bit order: bit i of byte n is chunk (n*8 + i).
/// </summary>
public sealed class ChunkBitmap
{
    private readonly byte[] _bits;

    public ChunkBitmap(int chunkCount)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(chunkCount);

        ChunkCount = chunkCount;
        _bits = new byte[(chunkCount + 7) / 8];
    }

    public int ChunkCount { get; }

    public int CompletedCount { get; private set; }

    public bool IsComplete => CompletedCount == ChunkCount;

    public bool this[int index]
    {
        get
        {
            Validate(index);
            return (_bits[index / 8] & (1 << (index % 8))) != 0;
        }
        set
        {
            Validate(index);

            var current = this[index];
            if (current == value) return;

            if (value)
            {
                _bits[index / 8] |= (byte)(1 << (index % 8));
                CompletedCount++;
            }
            else
            {
                _bits[index / 8] &= (byte)~(1 << (index % 8));
                CompletedCount--;
            }
        }
    }

    /// <summary>Contiguous runs of missing chunks, in chunk-index space, end-exclusive.</summary>
    public IEnumerable<Range> MissingRanges()
    {
        var start = -1;

        for (var i = 0; i < ChunkCount; i++)
        {
            if (!this[i])
            {
                if (start < 0) start = i;
            }
            else if (start >= 0)
            {
                yield return new Range(start, i);
                start = -1;
            }
        }

        if (start >= 0) yield return new Range(start, ChunkCount);
    }

    public string ToBase64() => Convert.ToBase64String(_bits);

    public static ChunkBitmap FromBase64(string base64, int chunkCount)
    {
        var bitmap = new ChunkBitmap(chunkCount);
        var bytes = Convert.FromBase64String(base64);

        for (var i = 0; i < chunkCount; i++)
        {
            var byteIndex = i / 8;
            if (byteIndex >= bytes.Length) break;

            if ((bytes[byteIndex] & (1 << (i % 8))) != 0) bitmap[i] = true;
        }

        return bitmap;
    }

    public static int ChunkCountFor(long fileSize, int chunkSize)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(fileSize);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(chunkSize);

        return (int)((fileSize + chunkSize - 1) / chunkSize);
    }

    private void Validate(int index)
    {
        if (index < 0 || index >= ChunkCount)
            throw new ArgumentOutOfRangeException(nameof(index), $"Chunk {index} is outside 0..{ChunkCount - 1}.");
    }
}
