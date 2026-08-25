using System.Net;
using Slipstream.Core.Control;
using Slipstream.Core.Discovery;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core;

/// <summary>
/// Wires the six modules together. One instance per running app.
/// </summary>
public sealed class SlipstreamPeer : IAsyncDisposable
{
    private readonly INetworkInfo _networkInfo = new NetworkInfo();
    private readonly MulticastStrategy _multicast;
    private readonly DiscoveryCoordinator _coordinator;
    private ControlServer? _server;

    public SlipstreamPeer(string stateDirectory, string displayName)
    {
        Identity = DeviceIdentity.LoadOrCreate(stateDirectory, displayName);
        Peers = new PairedPeerStore(stateDirectory);
        Client = new ControlClient(Identity, Peers);

        var cache = new EndpointCache(stateDirectory);
        var probe = Client.CreateProbe(TimeSpan.FromSeconds(3));

        _multicast = new MulticastStrategy(Identity, Peers, probe);

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

    public LocalNetwork? Network => _networkInfo.Current();

    /// <summary>Starts the listener and the multicast query responder.</summary>
    public Task StartAsync(CancellationToken cancellationToken)
    {
        var network = _networkInfo.Current()
            ?? throw new InvalidOperationException("No local network is available.");

        _server = new ControlServer(Identity, Peers, network.LocalAddress, SlipstreamPorts.Control);

        return Task.WhenAll(
            _server.RunAsync(cancellationToken),
            _multicast.RespondToQueriesAsync(cancellationToken));
    }

    public ControlServer Server =>
        _server ?? throw new InvalidOperationException("Call StartAsync first.");

    public Task<DiscoveryResult?> FindPeerAsync(TimeSpan timeout, CancellationToken cancellationToken) =>
        _coordinator.DiscoverAsync(timeout, cancellationToken);

    public async ValueTask DisposeAsync()
    {
        if (_server is not null) await _server.DisposeAsync();
        await _multicast.DisposeAsync();
    }
}
