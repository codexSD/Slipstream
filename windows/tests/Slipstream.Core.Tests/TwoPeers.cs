using System.Net;
using System.Runtime.Versioning;
using System.Text.Json;
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests;

/// <summary>
/// Two fully wired peers on loopback, already paired with each other and connected.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class TwoPeers : IAsyncDisposable
{
    public required SlipstreamPeer Server { get; init; }
    public required SlipstreamPeer Client { get; init; }
    public required ControlConnection Connection { get; init; }
    public required IPEndPoint ServerEndPoint { get; init; }

    /// <param name="serverUsesFixedPorts">
    /// The client always uses ephemeral ports (parallel-safe). The server does too by
    /// default, but <c>Slipstream.App.Services.PeerHost.PullAsync</c> hardcodes
    /// <see cref="SlipstreamPorts.Bulk"/> for the remote bulk endpoint rather than
    /// discovering it, so a test that exercises <c>PeerHost.PullAsync</c> end-to-end needs
    /// the server's bulk port to actually be <see cref="SlipstreamPorts.Bulk"/>. Pass true
    /// for that case; it makes the server bind the well-known ports, so at most one such
    /// test may run at a time on this machine.
    /// </param>
    public static async Task<TwoPeers> StartAsync(
        string rootDirectory, CancellationToken cancellationToken, bool serverUsesFixedPorts = false)
    {
        var server = new SlipstreamPeer(Path.Combine(rootDirectory, "server-state"), "Server PC")
        {
            BindAddress = IPAddress.Loopback,
            DownloadDirectory = Path.Combine(rootDirectory, "server-downloads"),
            UseEphemeralPorts = !serverUsesFixedPorts,
        };

        // The server's control port is ephemeral (parallel-safe), so this machine's real
        // discovery strategies (gateway probe, subnet sweep, multicast) — which all assume
        // the well-known SlipstreamPorts.Control — can never find it. Seed the S1 endpoint
        // cache directly for the client-state directory below, before the client
        // SlipstreamPeer (and the EndpointCache it loads at construction) exists, so a real
        // SlipstreamPeer.FindPeerAsync call against the client succeeds the way it would for
        // a warm start on a real network.
        _ = server.StartAsync(cancellationToken);
        SeedEndpointCache(Path.Combine(rootDirectory, "client-state"), server.Server.ListenEndPoint);

        var client = new SlipstreamPeer(Path.Combine(rootDirectory, "client-state"), "Client Phone")
        {
            BindAddress = IPAddress.Loopback,
            DownloadDirectory = Path.Combine(rootDirectory, "client-downloads"),
            UseEphemeralPorts = true,
        };

        server.Peers.Pair(new PairedPeer(client.Identity.DeviceId, client.Identity.Fingerprint, "Client Phone", DateTimeOffset.UtcNow));
        client.Peers.Pair(new PairedPeer(server.Identity.DeviceId, server.Identity.Fingerprint, "Server PC", DateTimeOffset.UtcNow));

        _ = client.StartAsync(cancellationToken);

        await Task.Delay(400, cancellationToken);

        var connection = await client.Client.ConnectAsync(
            server.Server.ListenEndPoint, TimeSpan.FromSeconds(10), cancellationToken)
            ?? throw new InvalidOperationException("The two peers failed to connect.");

        return new TwoPeers
        {
            Server = server,
            Client = client,
            Connection = connection,
            // The bulk (not control) endpoint: PullAsync hands this straight to
            // BulkClient.DownloadAsync, which speaks the plaintext bulk protocol.
            ServerEndPoint = server.BulkServer.ListenEndPoint,
        };
    }

    public async ValueTask DisposeAsync()
    {
        await Connection.DisposeAsync();
        await Client.DisposeAsync();
        await Server.DisposeAsync();
    }

    /// <summary>
    /// Forcibly severs every live control connection the server has accepted — including
    /// ones this rig did not itself create, such as a <c>PeerHost</c> connecting out via
    /// <see cref="Client"/>. Simulates a dropped link (spec §5): the peer process stays up,
    /// only the TCP/TLS stream dies, exactly as a Wi-Fi handoff or a cable pull would look
    /// to the client's control-channel reader.
    /// </summary>
    public async Task BreakControlConnectionAsync()
    {
        var connections = Server.Server.Connections;
        foreach (var connection in connections)
            await connection.DisposeAsync();
    }

    /// <summary>
    /// Writes the S1 discovery cache file directly, in the format <c>EndpointCache</c> reads
    /// at construction, keyed by whatever network this machine's real <see cref="NetworkInfo"/>
    /// reports. If no active non-loopback adapter is present, discovery will find nothing —
    /// same as <c>SlipstreamPeer.StartAsync</c> itself requires one to run at all.
    /// </summary>
    private static void SeedEndpointCache(string clientStateDirectory, IPEndPoint serverEndpoint)
    {
        var network = new NetworkInfo().Current();
        if (network is null) return;

        Directory.CreateDirectory(clientStateDirectory);
        var path = Path.Combine(clientStateDirectory, "endpoint-cache.json");
        var entries = new Dictionary<string, string> { [network.Key] = serverEndpoint.ToString() };
        File.WriteAllText(path, JsonSerializer.Serialize(entries, new JsonSerializerOptions { WriteIndented = true }));
    }
}
