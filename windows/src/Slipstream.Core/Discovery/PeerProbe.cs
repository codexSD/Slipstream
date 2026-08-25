using System.Net;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Discovery;

public sealed record DiscoveredPeer(PairedPeer Peer, IPEndPoint Endpoint);

/// <summary>
/// Answers "is our paired peer at this endpoint?". Returns null for no, and for any
/// unreachable host — an unreachable address is an expected outcome during a sweep,
/// not an error. Implemented for real in Task 14.
/// </summary>
public delegate Task<DiscoveredPeer?> PeerProbe(IPEndPoint endpoint, CancellationToken cancellationToken);
