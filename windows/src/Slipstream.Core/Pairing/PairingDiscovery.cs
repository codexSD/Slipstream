using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Pairing;

public sealed record UnpairedPeer(
    string DeviceId, string DisplayName, string Fingerprint, IPEndPoint Endpoint);

/// <summary>
/// Finds a device that is not yet paired with us. Only ever active inside an open
/// pairing window — outside it this returns null without listening at all.
/// </summary>
public sealed class PairingDiscovery(
    DeviceIdentity identity,
    MulticastStrategy multicast,
    PairingWindow window)
{
    public async Task<UnpairedPeer?> FindAsync(TimeSpan timeout, CancellationToken cancellationToken)
    {
        if (!window.IsOpen) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        linked.CancelAfter(timeout);

        try
        {
            await foreach (var (announcement, source) in multicast.SubscribeAsync(linked.Token))
            {
                if (!window.IsOpen) return null;

                // Never discover ourselves.
                if (string.Equals(announcement.Fingerprint, identity.Fingerprint, StringComparison.OrdinalIgnoreCase))
                    continue;

                if (!LanGuard.IsLocal(source.Address)) continue;

                return new UnpairedPeer(
                    announcement.DeviceId,
                    announcement.DisplayName,
                    announcement.Fingerprint,
                    new IPEndPoint(source.Address, announcement.ControlPort));
            }
        }
        catch (OperationCanceledException)
        {
            // Timed out, or the caller gave up.
        }

        return null;
    }
}
