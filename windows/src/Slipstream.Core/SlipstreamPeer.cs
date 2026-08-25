using System.Net;
using System.Net.NetworkInformation;
using System.Runtime.Versioning;
using Slipstream.Core.Control;
using Slipstream.Core.Control.Handlers;
using Slipstream.Core.Discovery;
using Slipstream.Core.Files;
using Slipstream.Core.Identity;
using Slipstream.Core.Media;
using Slipstream.Core.Net;
using Slipstream.Core.Pairing;
using Slipstream.Core.Platform;
using Slipstream.Core.Transfer;

namespace Slipstream.Core;

/// <summary>
/// Wires the six modules together. One instance per running app.
/// </summary>
/// <remarks>
/// StartAsync constructs a <c>ThumbnailProvider</c>, <c>PlaylistLauncher</c>, and
/// <c>SlipstreamSession</c> — each already <c>[SupportedOSPlatform("windows")]</c> —
/// so this type carries the same guard rather than each internal call site repeating
/// it. Slipstream.Core targets plain net9.0 (not net9.0-windows), so without this the
/// platform-compatibility analyzer flags those constructions as CA1416 under this
/// solution's TreatWarningsAsErrors.
/// </remarks>
[SupportedOSPlatform("windows")]
public sealed class SlipstreamPeer : IAsyncDisposable
{
    private readonly string _stateDirectory;
    private readonly INetworkInfo _networkInfo = new NetworkInfo();
    private readonly MulticastStrategy _multicast;
    private readonly DiscoveryCoordinator _coordinator;
    private readonly PairingCoordinator _pairingCoordinator;
    private ControlServer? _server;
    private BulkServer? _bulkServer;
    private MediaServer? _mediaServer;
    private TransferEngine? _engine;
    private Func<string, CancellationToken, Task<bool>>? _confirmCode;

    public SlipstreamPeer(string stateDirectory, string displayName)
    {
        _stateDirectory = stateDirectory;
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

    // New init-only configuration, defaulted so existing callers are unaffected.
    public IPAddress? BindAddress { get; init; }
    public string DownloadDirectory { get; init; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Slipstream");
    public bool UseEphemeralPorts { get; init; }
    public int StreamCount { get; init; } = 4;

    public TokenVault Tokens { get; } = new();
    public BulkServer BulkServer => _bulkServer ?? throw new InvalidOperationException("Call StartAsync first.");
    public MediaServer MediaServer => _mediaServer ?? throw new InvalidOperationException("Call StartAsync first.");
    public TransferEngine Engine => _engine ?? throw new InvalidOperationException("Call StartAsync first.");

    /// <summary>Raised whenever discovery must run again — spec §5 network-change handling.</summary>
    public event Action? NetworkChanged;

    /// <summary>
    /// Test-only trigger for <see cref="NetworkChanged"/>: invokes the exact same event
    /// subscribers (e.g. <c>PeerHost</c>) observe in production, without depending on the
    /// OS actually delivering <see cref="NetworkChange.NetworkAddressChanged"/> (which is
    /// not something a test can raise deterministically on demand).
    /// </summary>
    public void RaiseNetworkChanged() => NetworkChanged?.Invoke();

    /// <summary>Starts the listener and the multicast query responder.</summary>
    public Task StartAsync(CancellationToken cancellationToken)
    {
        var network = _networkInfo.Current()
            ?? throw new InvalidOperationException("No local network is available.");

        // Every server binds the same address, and every port becomes ephemeral
        // together under UseEphemeralPorts — running two peers in one process (as
        // every test in this suite does) would otherwise collide on the fixed
        // control port the moment a second SlipstreamPeer starts.
        var bind = BindAddress ?? network.LocalAddress;

        _server = new ControlServer(Identity, Peers, bind, UseEphemeralPorts ? 0 : SlipstreamPorts.Control, Pairing);
        _bulkServer = new BulkServer(Tokens, bind, UseEphemeralPorts ? 0 : SlipstreamPorts.Bulk);
        _mediaServer = new MediaServer(Tokens, bind, UseEphemeralPorts ? 0 : SlipstreamPorts.Media);

        var thumbnails = new ThumbnailProvider(Path.Combine(_stateDirectory, "thumbnails"), Tokens);
        _mediaServer.ThumbnailResolver = thumbnails.Resolve;

        var session = new SlipstreamSession(
            Identity, new FileBrowser(), Tokens, _mediaServer, thumbnails,
            new PlaylistLauncher(Path.Combine(Path.GetTempPath(), "slipstream")),
            bind, StreamCount);

        _engine = new TransferEngine(Client, new BulkClient(), DownloadDirectory, StreamCount);

        // Every inbound control connection is pumped through the session dispatcher.
        _server.PeerConnected += async (connection, token) =>
        {
            while (await connection.ReceiveAsync(token) is { } message)
            {
                var reply = await session.HandleAsync(message, token);
                if (reply is not null) await connection.SendAsync(reply, token);
            }
        };

        _server.PairingConnected += async (connection, token) =>
        {
            if (_confirmCode is null) return; // no pairing attempt in flight
            await _pairingCoordinator.PairAsync(connection, isInitiator: false, _confirmCode, null, token);
        };

        // Spec §5: a network switch is routine. Tear down, rediscover, resume.
        // (The event lives on NetworkChange, not NetworkInterface.)
        NetworkChange.NetworkAddressChanged += (_, _) => NetworkChanged?.Invoke();

        // Spec §7: sweep orphaned .part files on start.
        PartFile.CollectStale(DownloadDirectory, TimeSpan.FromDays(7));

        return Task.WhenAll(
            _server.RunAsync(cancellationToken),
            _bulkServer.RunAsync(cancellationToken),
            _mediaServer.RunAsync(cancellationToken),
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
        if (_bulkServer is not null) await _bulkServer.DisposeAsync();
        if (_mediaServer is not null) await _mediaServer.DisposeAsync();
        await _multicast.DisposeAsync();
    }
}
