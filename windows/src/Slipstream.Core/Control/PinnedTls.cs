using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Control;

/// <summary>
/// Spec §4: validation is fingerprint-pin only. CA chain validation is meaningless
/// for self-signed local certificates and is explicitly disabled. There is no
/// prompt and no override path for a non-matching certificate.
/// </summary>
public static class PinnedTls
{
    public static async Task<SslStream> AuthenticateAsClientAsync(
        Stream inner,
        DeviceIdentity identity,
        Func<string, bool> acceptFingerprint,
        CancellationToken cancellationToken)
    {
        var stream = new SslStream(inner, leaveInnerStreamOpen: false);

        await stream.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = "slipstream",
            ClientCertificates = [identity.Certificate],
            EnabledSslProtocols = SslProtocols.Tls13 | SslProtocols.Tls12,
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                certificate is not null && acceptFingerprint(Fingerprint.Of(certificate.GetRawCertData())),
        }, cancellationToken);

        return stream;
    }

    public static async Task<SslStream> AuthenticateAsServerAsync(
        Stream inner,
        DeviceIdentity identity,
        CancellationToken cancellationToken)
    {
        // The server accepts any client certificate at the TLS layer; the caller
        // checks the resulting fingerprint against the paired peer afterwards.
        var stream = new SslStream(inner, leaveInnerStreamOpen: false);

        await stream.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
        {
            ServerCertificate = identity.Certificate,
            ClientCertificateRequired = true,
            EnabledSslProtocols = SslProtocols.Tls13 | SslProtocols.Tls12,
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            RemoteCertificateValidationCallback = (_, _, _, _) => true,
        }, cancellationToken);

        return stream;
    }

    /// <summary>The remote certificate's fingerprint, for pin comparison.</summary>
    public static string FingerprintOf(SslStream stream)
    {
        var certificate = stream.RemoteCertificate
            ?? throw new ControlProtocolException("The peer presented no certificate.");

        return Fingerprint.Of(certificate.GetRawCertData());
    }
}
