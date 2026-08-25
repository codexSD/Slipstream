using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class Crc32CTests
{
    [Fact]
    public void Matches_the_standard_castagnoli_check_value()
    {
        // The canonical CRC-32C check value for "123456789".
        Assert.Equal(0xE3069283u, Crc32C.Compute("123456789"u8));
    }

    [Fact]
    public void Empty_input_is_zero()
    {
        Assert.Equal(0u, Crc32C.Compute(ReadOnlySpan<byte>.Empty));
    }

    [Fact]
    public void Matches_the_shared_conformance_vectors()
    {
        var path = Path.Combine(VectorPaths.Root, "crc32c.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));

        foreach (var testCase in doc.RootElement.GetProperty("cases").EnumerateArray())
        {
            var input = Encoding.UTF8.GetBytes(testCase.GetProperty("input_utf8").GetString()!);
            var expected = Convert.ToUInt32(testCase.GetProperty("crc_hex").GetString()!, 16);

            Assert.Equal(expected, Crc32C.Compute(input));
        }
    }

    [Fact]
    public void Append_in_pieces_equals_a_single_pass()
    {
        var data = RandomNumberGenerator.GetBytes(100_000);

        var single = Crc32C.Compute(data);

        var running = 0u;
        for (var offset = 0; offset < data.Length; offset += 7777)
            running = Crc32C.Append(running, data.AsSpan(offset, Math.Min(7777, data.Length - offset)));

        Assert.Equal(single, running);
    }

    [Fact]
    public void Detects_a_single_flipped_bit()
    {
        var data = RandomNumberGenerator.GetBytes(1_048_576);
        var original = Crc32C.Compute(data);

        data[524_288] ^= 0x01;

        Assert.NotEqual(original, Crc32C.Compute(data));
    }

    [Fact]
    public void Handles_a_full_chunk_sized_buffer()
    {
        var chunk = RandomNumberGenerator.GetBytes(1_048_576);
        Assert.Equal(Crc32C.Compute(chunk), Crc32C.Compute(chunk));
    }
}