using System.Collections.Concurrent;
using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests.Discovery;

/// <summary>
/// A PeerProbe that answers for a fixed set of endpoints and records every attempt.
/// </summary>
public sealed class FakeProbe
{
    public static readonly PairedPeer Peer =
        new("peer-device", "deadbeef", "Test Phone", DateTimeOffset.UnixEpoch);

    private readonly HashSet<string> _responding;
    private readonly ConcurrentQueue<IPEndPoint> _attempts = new();

    public FakeProbe(params string[] respondingEndpoints) =>
        _responding = new HashSet<string>(respondingEndpoints, StringComparer.OrdinalIgnoreCase);

    /// <summary>Milliseconds each probe takes before answering. Used to test racing.</summary>
    public int Delay { get; set; }

    public IReadOnlyList<IPEndPoint> Attempts => _attempts.ToList();

    public PeerProbe Probe => async (endpoint, cancellationToken) =>
    {
        _attempts.Enqueue(endpoint);

        if (Delay > 0) await Task.Delay(Delay, cancellationToken);

        return _responding.Contains(endpoint.ToString())
            ? new DiscoveredPeer(Peer, endpoint)
            : null;
    };
}
