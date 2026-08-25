using System.Diagnostics;
using Slipstream.Core.Net;

namespace Slipstream.Core.Discovery;

public sealed record DiscoveryResult(DiscoveredPeer Peer, string StrategyName, TimeSpan Elapsed);

/// <summary>
/// Spec §5. Runs every strategy concurrently; the first to return a fingerprint-matched
/// peer wins and the rest are cancelled. A strategy that throws is treated as "found
/// nothing" — a blocked multicast socket must not prevent the sweep from winning.
/// </summary>
public sealed class DiscoveryCoordinator(
    INetworkInfo networkInfo,
    EndpointCache cache,
    IReadOnlyList<IDiscoveryStrategy> strategies)
{
    public async Task<DiscoveryResult?> DiscoverAsync(TimeSpan timeout, CancellationToken cancellationToken)
    {
        var network = networkInfo.Current();
        if (network is null) return null;
        if (strategies.Count == 0) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        linked.CancelAfter(timeout);

        var stopwatch = Stopwatch.StartNew();

        var running = strategies
            .Select(strategy => RunAsync(strategy, network, stopwatch, linked.Token))
            .ToList();

        try
        {
            while (running.Count > 0)
            {
                var completed = await Task.WhenAny(running);
                running.Remove(completed);

                var result = await completed;
                if (result is null) continue;

                await linked.CancelAsync();
                cache.Set(network.Key, result.Peer.Endpoint);
                return result;
            }
        }
        finally
        {
            await linked.CancelAsync();
            await Task.WhenAll(running.Select(SwallowAsync));
        }

        return null;
    }

    private static async Task<DiscoveryResult?> RunAsync(
        IDiscoveryStrategy strategy,
        LocalNetwork network,
        Stopwatch stopwatch,
        CancellationToken cancellationToken)
    {
        try
        {
            var peer = await strategy.FindAsync(network, cancellationToken);
            return peer is null ? null : new DiscoveryResult(peer, strategy.Name, stopwatch.Elapsed);
        }
        catch (Exception)
        {
            // A failing strategy is a strategy that found nothing. Never let one
            // adapter's firewall policy take down discovery as a whole.
            return null;
        }
    }

    private static async Task SwallowAsync(Task task)
    {
        try { await task; } catch { /* losers are cancelled by design */ }
    }
}
