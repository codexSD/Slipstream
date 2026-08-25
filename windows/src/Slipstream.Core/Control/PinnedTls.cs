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

        await stream.AuthenticateAsClientAsync(
            CreateClientOptions(identity, acceptFingerprint), cancellationToken);

        return stream;
    }

    /// <summary>
    /// The client-side handshake options. Exposed separately so the certificate
    /// selection behaviour can be asserted without a live peer.
    /// </summary>
    internal static SslClientAuthenticationOptions CreateClientOptions(
        DeviceIdentity identity,
        Func<string, bool> acceptFingerprint) =>
        new()
        {
            TargetHost = "slipstream",
            ClientCertificates = [identity.Certificate],

            // Android's JSSE sends a certificate_authorities list that cannot contain a
            // self-signed issuer, so .NET's default selection logic sends NO client
            // certificate and the peer - which requires one - stalls the handshake.
            // Selecting unconditionally bypasses that filter. Safe here because there is
            // exactly one certificate and exactly one peer; the trust decision is the
            // fingerprint pin below, never the issuer list.
            LocalCertificateSelectionCallback = (_, _, _, _, _) => identity.Certificate,

            EnabledSslProtocols = SslProtocols.Tls13 | SslProtocols.Tls12,
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                certificate is not null && acceptFingerprint(Fingerprint.Of(certificate.GetRawCertData())),
        };

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
