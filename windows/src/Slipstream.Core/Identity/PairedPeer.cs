namespace Slipstream.Core.Identity;

public sealed record PairedPeer(
    string DeviceId,
    string Fingerprint,
    string DisplayName,
    DateTimeOffset PairedAt);
