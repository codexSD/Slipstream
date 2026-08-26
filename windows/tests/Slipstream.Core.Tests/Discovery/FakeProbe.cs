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

    private int _inFlight;
    private int _peakInFlight;

    /// <summary>
    /// The largest number of probes that were ever inside this delegate at the same moment.
    /// A strictly serial sweep can never exceed 1, whatever the machine's load — which makes
    /// this a direct measurement of concurrency rather than the wall-clock proxy an elapsed
    /// time assertion uses.
    /// </summary>
    public int PeakInFlight => Volatile.Read(ref _peakInFlight);

    public PeerProbe Probe => async (endpoint, cancellationToken) =>
    {
        _attempts.Enqueue(endpoint);

        var live = Interlocked.Increment(ref _inFlight);
        var peak = Volatile.Read(ref _peakInFlight);
        while (live > peak && Interlocked.CompareExchange(ref _peakInFlight, live, peak) != peak)
            peak = Volatile.Read(ref _peakInFlight);

        try
        {
            if (Delay > 0) await Task.Delay(Delay, cancellationToken);
        }
        finally
        {
            Interlocked.Decrement(ref _inFlight);
        }

        return _responding.Contains(endpoint.ToString())
            ? new DiscoveredPeer(Peer, endpoint)
            : null;
    };
}
