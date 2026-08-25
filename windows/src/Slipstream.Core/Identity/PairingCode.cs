using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Slipstream.Core.Identity;

/// <summary>
/// Spec §4. Sorting the two fingerprints before hashing makes the derivation
/// order-independent, so both devices compute the same code without negotiating
/// who is "first".
/// </summary>
public static class PairingCode
{
    public static string Derive(string fingerprintA, string fingerprintB)
    {
        var a = fingerprintA.Trim().ToLowerInvariant();
        var b = fingerprintB.Trim().ToLowerInvariant();

        var (first, second) = string.CompareOrdinal(a, b) <= 0 ? (a, b) : (b, a);

        var digest = SHA256.HashData(Encoding.ASCII.GetBytes(first + second));
        var value = BinaryPrimitives.ReadUInt32BigEndian(digest.AsSpan(0, 4));

        return (value % 1_000_000).ToString("D6");
    }
}
