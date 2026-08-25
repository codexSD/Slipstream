using System.Net;
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S4. The backstop for access points that silently drop multicast.
/// Bounded to a /24 by SubnetMath — a wider sweep is refused, not attempted.
/// </summary>
public sealed class SubnetSweepStrategy(PeerProbe probe, int maxConcurrency = 254) : IDiscoveryStrategy
{
    public string Name => "subnet-sweep";

    public async Task<DiscoveredPeer?> FindAsync(LocalNetwork network, CancellationToken cancellationToken)
    {
        var hosts = SubnetMath
            .EnumerateHosts(network.LocalAddress, network.PrefixLength)
            .ToList();

        if (hosts.Count == 0) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        using var slots = new SemaphoreSlim(maxConcurrency);

        DiscoveredPeer? winner = null;

        var probes = hosts.Select(async host =>
        {
            await slots.WaitAsync(linked.Token);
            try
            {
                var found = await probe(new IPEndPoint(host, SlipstreamPorts.Control), linked.Token);
                if (found is not null)
                {
                    Interlocked.CompareExchange(ref winner, found, null);
                    await linked.CancelAsync();
                }
            }
            catch (OperationCanceledException)
            {
                // Another probe won, or the caller cancelled.
            }
            finally
            {
                slots.Release();
            }
        });

        try
        {
            await Task.WhenAll(probes);
        }
        catch (OperationCanceledException)
        {
            // Expected once a winner cancels the rest.
        }

        return winner;
    }
}
