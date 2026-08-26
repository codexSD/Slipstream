using System.Net;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Pairing;

public sealed record UnpairedPeer(
    string DeviceId, string DisplayName, string Fingerprint, IPEndPoint Endpoint);

/// <summary>
/// Answers "is there a device here that could pair with us?". Connects unpinned — there is
/// nothing to pin against until pairing completes — and returns null for any unreachable
/// host, which during a sweep is the normal answer, not an error.
/// </summary>
public delegate Task<UnpairedPeer?> PairingProbe(IPEndPoint endpoint, CancellationToken cancellationToken);

/// <summary>
/// Finds a device that is not yet paired with us. Only ever active inside an open pairing
/// window — outside it this returns null without listening or probing at all.
/// </summary>
/// <remarks>
/// Races the same strategy ladder as <see cref="DiscoveryCoordinator"/>, and for the same
/// reason: multicast is not reliable enough to be anyone's only strategy. The gateway probe
/// is decisive on the product's primary topology (spec §1, phone hotspot), where the phone
/// is the PC's default gateway and its softAP delivers no multicast at all — measured on
/// real hardware as zero datagrams in eight seconds against a control port that answers a
/// TLS handshake in tens of milliseconds. The subnet sweep is the backstop for access points
/// that drop multicast without being anyone's gateway.
///
/// The one difference from paired discovery is the trust filter, and it is deliberate:
/// paired discovery requires a fingerprint match, while pairing discovery accepts any peer.
/// The six-digit code, compared by two humans, is what establishes trust here
/// (protocol/pairing.md) — so this ladder finds only a *candidate*. It never pairs and never
/// persists anything; <see cref="PairingCoordinator"/> owns mutual confirmation.
///
/// Every strategy is gated on <see cref="PairingWindow.IsOpen"/>, checked before the first
/// probe and again before any result is returned. Nothing here touches the network outside
/// the window.
/// </remarks>
public sealed class PairingDiscovery(
    DeviceIdentity identity,
    MulticastStrategy multicast,
    PairingWindow window,
    INetworkInfo networkInfo,
    PairingProbe probe,
    PairingProbe? sweepProbe = null,
    int sweepConcurrency = 254)
{
    /// <summary>How often to re-check that the window is still open while a search is running.</summary>
    private static readonly TimeSpan WindowPoll = TimeSpan.FromMilliseconds(200);

    public async Task<UnpairedPeer?> FindAsync(TimeSpan timeout, CancellationToken cancellationToken)
    {
        if (!window.IsOpen) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        linked.CancelAfter(timeout);

        var network = networkInfo.Current();

        var running = new List<Task<UnpairedPeer?>>
        {
            FromMulticastAsync(linked.Token),
            // The window can close mid-search (user cancelled, or 120 s elapsed). Nothing
            // else here polls, so this arm ends the race the moment the gate shuts.
            WatchWindowAsync(linked.Token),
        };

        if (network is not null)
        {
            running.Add(FromGatewayAsync(network, linked.Token));
            running.Add(FromSweepAsync(network, linked.Token));
        }

        try
        {
            while (running.Count > 0)
            {
                var completed = await Task.WhenAny(running);
                running.Remove(completed);

                // Checked on every completion, not just on a hit: a result is only a result
                // while the window is open, and the watcher arm below completes when it shuts.
                if (!window.IsOpen) return null;

                var found = await SwallowAsync(completed);
                if (found is null) continue;

                await linked.CancelAsync();
                return found;
            }
        }
        finally
        {
            await linked.CancelAsync();
            await Task.WhenAll(running.Select(SwallowLoserAsync));
        }

        return null;
    }

    /// <summary>
    /// Spec §5 S3. Subscribes to the shared reader loop's fan-out — never a second
    /// <c>ReceiveAsync</c> on that socket, which is what stole datagrams before.
    /// An announcement already carries the peer's identity, so no probe is needed.
    /// </summary>
    private async Task<UnpairedPeer?> FromMulticastAsync(CancellationToken cancellationToken)
    {
        await foreach (var (announcement, source) in multicast.SubscribeAsync(cancellationToken))
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

        return null;
    }

    /// <summary>
    /// Spec §5 S2. One probe at the default gateway. Decisive in hotspot mode, where the
    /// phone *is* the gateway, and free everywhere else.
    /// </summary>
    private async Task<UnpairedPeer?> FromGatewayAsync(LocalNetwork network, CancellationToken cancellationToken)
    {
        if (network.Gateway is null) return null;
        if (!LanGuard.IsLocal(network.Gateway)) return null;
        if (!window.IsOpen) return null;

        return await probe(new IPEndPoint(network.Gateway, SlipstreamPorts.Control), cancellationToken);
    }

    /// <summary>
    /// Spec §5 S4. The backstop. Bounded to a /24 by <see cref="SubnetMath"/> — a wider
    /// sweep is refused, not attempted.
    /// </summary>
    private async Task<UnpairedPeer?> FromSweepAsync(LocalNetwork network, CancellationToken cancellationToken)
    {
        var sweep = sweepProbe ?? probe;

        var hosts = SubnetMath
            .EnumerateHosts(network.LocalAddress, network.PrefixLength)
            .Where(host => !host.Equals(network.LocalAddress))
            .ToList();

        if (hosts.Count == 0) return null;
        if (!window.IsOpen) return null;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        using var slots = new SemaphoreSlim(sweepConcurrency);

        UnpairedPeer? winner = null;

        var probes = hosts.Select(async host =>
        {
            await slots.WaitAsync(linked.Token);
            try
            {
                // Re-checked per host: a window that closed halfway through a 254-way sweep
                // must stop the remaining probes, not merely discard their results.
                if (!window.IsOpen) return;

                var found = await sweep(new IPEndPoint(host, SlipstreamPorts.Control), linked.Token);
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

    /// <summary>
    /// Completes (with no peer) as soon as the pairing window shuts, so a closed window ends
    /// the search promptly instead of leaving the caller blocked until the timeout.
    /// </summary>
    private async Task<UnpairedPeer?> WatchWindowAsync(CancellationToken cancellationToken)
    {
        while (window.IsOpen)
            await Task.Delay(WindowPoll, cancellationToken);

        return null;
    }

    private static async Task<UnpairedPeer?> SwallowAsync(Task<UnpairedPeer?> task)
    {
        // A strategy that throws is a strategy that found nothing — one adapter's firewall
        // policy must never take pairing discovery down as a whole.
        try { return await task; } catch (Exception) { return null; }
    }

    private static async Task SwallowLoserAsync(Task task)
    {
        try { await task; } catch (Exception) { /* losers are cancelled by design */ }
    }
}
