using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// One way of locating the paired peer. Strategies never throw for "not found" —
/// they return null, so the coordinator can race them without exception handling.
/// </summary>
public interface IDiscoveryStrategy
{
    string Name { get; }

    Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken);
}
