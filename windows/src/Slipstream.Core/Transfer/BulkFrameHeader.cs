using System.Buffers.Binary;

namespace Slipstream.Core.Transfer;

/// <summary>
/// The 64-byte stream header from protocol/bulk-format.md. Big-endian throughout.
/// </summary>
public readonly record struct BulkFrameHeader(
    ushort Version,
    ushort StreamIndex,
    Guid Token,
    Guid TransferId,
    long RangeStart,
    long RangeLength,
    int ChunkSize)
{
    public const int Size = 64;

    private static ReadOnlySpan<byte> Magic => "SLPS"u8;

    public void WriteTo(Span<byte> destination)
    {
        if (destination.Length < Size)
            throw new ArgumentException($"Header needs {Size} bytes.", nameof(destination));

        destination[..Size].Clear();

        Magic.CopyTo(destination);
        BinaryPrimitives.WriteUInt16BigEndian(destination[4..], Version);
        BinaryPrimitives.WriteUInt16BigEndian(destination[6..], StreamIndex);

        Token.TryWriteBytes(destination.Slice(8, 16), bigEndian: true, out _);
        TransferId.TryWriteBytes(destination.Slice(24, 16), bigEndian: true, out _);

        BinaryPrimitives.WriteInt64BigEndian(destination[40..], RangeStart);
        BinaryPrimitives.WriteInt64BigEndian(destination[48..], RangeLength);
        BinaryPrimitives.WriteInt32BigEndian(destination[56..], ChunkSize);
        // bytes 60..63 stay zero
    }

    public static bool TryRead(ReadOnlySpan<byte> source, out BulkFrameHeader header)
    {
        header = default;

        if (source.Length < Size) return false;
        if (!source[..4].SequenceEqual(Magic)) return false;

        var version = BinaryPrimitives.ReadUInt16BigEndian(source[4..]);
        if (version != SlipstreamPorts.ProtocolVersion) return false;

        var rangeStart = BinaryPrimitives.ReadInt64BigEndian(source[40..]);
        var rangeLength = BinaryPrimitives.ReadInt64BigEndian(source[48..]);
        var chunkSize = BinaryPrimitives.ReadInt32BigEndian(source[56..]);

        if (rangeStart < 0 || rangeLength < 0 || chunkSize <= 0) return false;

        header = new BulkFrameHeader(
            version,
            BinaryPrimitives.ReadUInt16BigEndian(source[6..]),
            new Guid(source.Slice(8, 16), bigEndian: true),
            new Guid(source.Slice(24, 16), bigEndian: true),
            rangeStart,
            rangeLength,
            chunkSize);

        return true;
    }
}
