using System.Net;
using System.Runtime.Versioning;
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;

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

    public static async Task<TwoPeers> StartAsync(string rootDirectory, CancellationToken cancellationToken)
    {
        var server = new SlipstreamPeer(Path.Combine(rootDirectory, "server-state"), "Server PC")
        {
            BindAddress = IPAddress.Loopback,
            DownloadDirectory = Path.Combine(rootDirectory, "server-downloads"),
            UseEphemeralPorts = true,
        };

        var client = new SlipstreamPeer(Path.Combine(rootDirectory, "client-state"), "Client Phone")
        {
            BindAddress = IPAddress.Loopback,
            DownloadDirectory = Path.Combine(rootDirectory, "client-downloads"),
            UseEphemeralPorts = true,
        };

        server.Peers.Pair(new PairedPeer(client.Identity.DeviceId, client.Identity.Fingerprint, "Client Phone", DateTimeOffset.UtcNow));
        client.Peers.Pair(new PairedPeer(server.Identity.DeviceId, server.Identity.Fingerprint, "Server PC", DateTimeOffset.UtcNow));

        _ = server.StartAsync(cancellationToken);
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
}
