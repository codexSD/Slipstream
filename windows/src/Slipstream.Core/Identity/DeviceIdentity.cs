using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Slipstream.Core.Identity;

/// <summary>
/// Generated once per install (spec §4). The certificate is self-signed and validated
/// by fingerprint pin only — no CA chain is ever consulted.
/// </summary>
public sealed class DeviceIdentity
{
    private const string CertFileName = "identity.pfx";
    private const string IdFileName = "device-id";

    public string DeviceId { get; }
    public string DisplayName { get; }
    public X509Certificate2 Certificate { get; }
    public string Fingerprint { get; }

    private DeviceIdentity(string deviceId, string displayName, X509Certificate2 certificate)
    {
        DeviceId = deviceId;
        DisplayName = displayName;
        Certificate = certificate;
        Fingerprint = Identity.Fingerprint.Of(certificate);
    }

    public static DeviceIdentity CreateNew(string displayName)
    {
        var deviceId = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        return new DeviceIdentity(deviceId, displayName, CreateCertificate(deviceId));
    }

    public static DeviceIdentity LoadOrCreate(string directory, string displayName)
    {
        Directory.CreateDirectory(directory);
        var certPath = Path.Combine(directory, CertFileName);
        var idPath = Path.Combine(directory, IdFileName);

        if (File.Exists(certPath) && File.Exists(idPath))
        {
            var deviceId = File.ReadAllText(idPath).Trim();
            var certificate = X509CertificateLoader.LoadPkcs12(
                File.ReadAllBytes(certPath),
                password: null,
                keyStorageFlags: X509KeyStorageFlags.Exportable);
            return new DeviceIdentity(deviceId, displayName, certificate);
        }

        var created = CreateNew(displayName);
        File.WriteAllText(idPath, created.DeviceId);
        File.WriteAllBytes(certPath, created.Certificate.Export(X509ContentType.Pkcs12));
        return created;
    }

    private static X509Certificate2 CreateCertificate(string deviceId)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest(
            new X500DistinguishedName($"CN=slipstream-{deviceId}"),
            key,
            HashAlgorithmName.SHA256);

        request.CertificateExtensions.Add(
            new X509BasicConstraintsExtension(certificateAuthority: false, false, 0, critical: true));
        request.CertificateExtensions.Add(
            new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, critical: true));

        // Long-lived: the trust anchor is the pinned fingerprint, not an expiry date.
        var certificate = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1),
            DateTimeOffset.UtcNow.AddYears(20));

        // Round-trip through PKCS#12 so the private key is reliably usable by SslStream.
        return X509CertificateLoader.LoadPkcs12(
            certificate.Export(X509ContentType.Pkcs12),
            password: null,
            keyStorageFlags: X509KeyStorageFlags.Exportable);
    }
}
