using System.Net;
using Slipstream.Core.Control;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;
using Slipstream.Core.Pairing;

namespace Slipstream.Core;

/// <summary>
/// Wires the six modules together. One instance per running app.
/// </summary>
public sealed class SlipstreamPeer : IAsyncDisposable
{
    private readonly INetworkInfo _networkInfo = new NetworkInfo();
    private readonly MulticastStrategy _multicast;
    private readonly DiscoveryCoordinator _coordinator;
    private readonly PairingCoordinator _pairingCoordinator;
    private ControlServer? _server;
    private Func<string, CancellationToken, Task<bool>>? _confirmCode;

    public SlipstreamPeer(string stateDirectory, string displayName)
    {
        Identity = DeviceIdentity.LoadOrCreate(stateDirectory, displayName);
        Peers = new PairedPeerStore(stateDirectory);
        Client = new ControlClient(Identity, Peers);
        Pairing = new PairingWindow();

        var cache = new EndpointCache(stateDirectory);
        var probe = Client.CreateProbe(TimeSpan.FromSeconds(3));

        _multicast = new MulticastStrategy(Identity, Peers, probe);
        PairingDiscovery = new PairingDiscovery(Identity, _multicast, Pairing);
        _pairingCoordinator = new PairingCoordinator(Identity, Peers, Client, Pairing);

        _coordinator = new DiscoveryCoordinator(_networkInfo, cache,
        [
            new CachedEndpointStrategy(cache, probe),
            new GatewayProbeStrategy(probe),
            _multicast,
            new SubnetSweepStrategy(Client.CreateProbe(TimeSpan.FromMilliseconds(600))),
        ]);
    }

    public DeviceIdentity Identity { get; }

    public PairedPeerStore Peers { get; }

    public ControlClient Client { get; }

    public PairingWindow Pairing { get; }

    public PairingDiscovery PairingDiscovery { get; }

    public LocalNetwork? Network => _networkInfo.Current();

    /// <summary>Starts the listener and the multicast query responder.</summary>
    public Task StartAsync(CancellationToken cancellationToken)
    {
        var network = _networkInfo.Current()
            ?? throw new InvalidOperationException("No local network is available.");

        _server = new ControlServer(Identity, Peers, network.LocalAddress, SlipstreamPorts.Control, Pairing);

        _server.PairingConnected += async (connection, token) =>
        {
            if (_confirmCode is null) return; // no pairing attempt in flight
            await _pairingCoordinator.PairAsync(connection, isInitiator: false, _confirmCode, null, token);
        };

        return Task.WhenAll(
            _server.RunAsync(cancellationToken),
            _multicast.RespondToQueriesAsync(cancellationToken));
    }

    public ControlServer Server =>
        _server ?? throw new InvalidOperationException("Call StartAsync first.");

    public Task<DiscoveryResult?> FindPeerAsync(TimeSpan timeout, CancellationToken cancellationToken) =>
        _coordinator.DiscoverAsync(timeout, cancellationToken);

    /// <summary>
    /// Opens the pairing window, finds an unpaired peer, and drives the pairing exchange
    /// as the initiator. The window closes on completion, failure, or timeout.
    /// </summary>
    public async Task<PairedPeer?> PairAsync(
        Func<string, CancellationToken, Task<bool>> confirmCode,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        Pairing.Open();
        _confirmCode = confirmCode;

        try
        {
            var found = await PairingDiscovery.FindAsync(timeout, cancellationToken);
            if (found is null) return null;

            await using var connection = await Client.ConnectForPairingAsync(
                found.Endpoint, timeout, cancellationToken);
            if (connection is null) return null;

            return await _pairingCoordinator.PairAsync(
                connection, isInitiator: true, confirmCode, null, cancellationToken);
        }
        finally
        {
            _confirmCode = null;
            Pairing.Close();
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_server is not null) await _server.DisposeAsync();
        await _multicast.DisposeAsync();
    }
}
