using System.Net;
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S2. Decisive for the phone-hotspot case, where the phone is the PC's
/// default gateway: one probe, no scanning, no multicast, cannot be defeated by
/// access-point behaviour.
/// </summary>
public sealed class GatewayProbeStrategy(PeerProbe probe) : IDiscoveryStrategy
{
    public string Name => "gateway-probe";

    public async Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken)
    {
        if (network.Gateway is null) return null;
        if (!LanGuard.IsLocal(network.Gateway)) return null;

        return await probe(new IPEndPoint(network.Gateway, SlipstreamPorts.Control), cancellationToken);
    }
}
