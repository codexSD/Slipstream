using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S1. The common case: both target networks are stable, so the last known
/// address is usually still correct and answers in about 50 ms.
/// </summary>
public sealed class CachedEndpointStrategy(EndpointCache cache, PeerProbe probe) : IDiscoveryStrategy
{
    public string Name => "cached-endpoint";

    public async Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken)
    {
        var endpoint = cache.Get(network.Key);
        if (endpoint is null) return null;

        // Re-validate on read: the cache is on-disk state, not a trusted source.
        if (!LanGuard.IsLocal(endpoint.Address)) return null;

        return await probe(endpoint, cancellationToken);
    }
}
