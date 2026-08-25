using System.Runtime.Intrinsics.Arm;
using System.Runtime.Intrinsics.X86;

namespace Slipstream.Core.Transfer;

/// <summary>
/// CRC-32C (Castagnoli). Hardware-accelerated via SSE4.2 on x64 and the ARM64 CRC
/// extension, with a table-driven fallback. BCL only — no NuGet package.
/// </summary>
public static class Crc32C
{
    private const uint Polynomial = 0x82F63B78; // reflected 0x1EDC6F41

    private static readonly uint[] Table = BuildTable();

    public static bool IsHardwareAccelerated => Sse42.IsSupported || Crc32.IsSupported;

    public static uint Compute(ReadOnlySpan<byte> data) => Append(0, data);

    /// <summary>Continues a running CRC. Append(0, x) == Compute(x).</summary>
    public static uint Append(uint crc, ReadOnlySpan<byte> data)
    {
        var state = ~crc;

        if (Sse42.X64.IsSupported)
            state = AppendSse42(state, data);
        else if (Crc32.Arm64.IsSupported)
            state = AppendArm64(state, data);
        else
            state = AppendSoftware(state, data);

        return ~state;
    }

    private static uint AppendSse42(uint state, ReadOnlySpan<byte> data)
    {
        var index = 0;

        // Eight bytes at a time while the buffer allows.
        while (data.Length - index >= sizeof(ulong))
        {
            var block = BitConverter.ToUInt64(data.Slice(index, sizeof(ulong)));
            state = (uint)Sse42.X64.Crc32(state, block);
            index += sizeof(ulong);
        }

        for (; index < data.Length; index++)
            state = Sse42.Crc32(state, data[index]);

        return state;
    }

    private static uint AppendArm64(uint state, ReadOnlySpan<byte> data)
    {
        var index = 0;

        while (data.Length - index >= sizeof(ulong))
        {
            var block = BitConverter.ToUInt64(data.Slice(index, sizeof(ulong)));
            state = Crc32.Arm64.ComputeCrc32C(state, block);
            index += sizeof(ulong);
        }

        for (; index < data.Length; index++)
            state = Crc32.ComputeCrc32C(state, data[index]);

        return state;
    }

    private static uint AppendSoftware(uint state, ReadOnlySpan<byte> data)
    {
        foreach (var b in data)
            state = Table[(state ^ b) & 0xFF] ^ (state >> 8);

        return state;
    }

    private static uint[] BuildTable()
    {
        var table = new uint[256];

        for (uint i = 0; i < 256; i++)
        {
            var entry = i;
            for (var bit = 0; bit < 8; bit++)
                entry = (entry & 1) != 0 ? (entry >> 1) ^ Polynomial : entry >> 1;

            table[i] = entry;
        }

        return table;
    }
}