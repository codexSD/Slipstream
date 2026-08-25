using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Slipstream.Core.Identity;

/// <summary>Spec §4: SHA-256 of the DER-encoded certificate, lowercase hex.</summary>
public static class Fingerprint
{
    public static string Of(X509Certificate2 certificate) => Of(certificate.RawData);

    public static string Of(ReadOnlySpan<byte> der) =>
        Convert.ToHexString(SHA256.HashData(der)).ToLowerInvariant();
}
