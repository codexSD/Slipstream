using System.Net;
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests.Control;

public class PairingHandshakeTests : IDisposable
{
    private readonly string _serverDir = Directory.CreateTempSubdirectory("slipstream-srv-").FullName;
    private readonly string _clientDir = Directory.CreateTempSubdirectory("slipstream-cli-").FullName;

    public void Dispose()
    {
        Directory.Delete(_serverDir, recursive: true);
        Directory.Delete(_clientDir, recursive: true);
    }

    [Fact]
    public async Task Paired_peers_exchange_hello_over_a_pinned_connection()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));

        var serverIdentity = DeviceIdentity.CreateNew("Server PC");
        var clientIdentity = DeviceIdentity.CreateNew("Client Phone");

        var serverPeers = new PairedPeerStore(_serverDir);
        serverPeers.Pair(new PairedPeer(clientIdentity.DeviceId, clientIdentity.Fingerprint, "Client Phone", DateTimeOffset.UnixEpoch));

        var clientPeers = new PairedPeerStore(_clientDir);
        clientPeers.Pair(new PairedPeer(serverIdentity.DeviceId, serverIdentity.Fingerprint, "Server PC", DateTimeOffset.UnixEpoch));

        await using var server = new ControlServer(serverIdentity, serverPeers, IPAddress.Loopback, port: 0);

        server.PeerConnected += async (connection, token) =>
        {
            var hello = await connection.ReceiveAsync(token);
            Assert.Equal("hello", hello!.Type);

            await connection.SendAsync(ControlMessage.Response("hello.ok", hello.Id!, new HelloPayload(
                SlipstreamPorts.ProtocolVersion, serverIdentity.DeviceId,
                serverIdentity.DisplayName, serverIdentity.Fingerprint)), token);
        };

        var running = server.RunAsync(cts.Token);

        var client = new ControlClient(clientIdentity, clientPeers);
        await using var connection = await client.ConnectAsync(server.ListenEndPoint, TimeSpan.FromSeconds(5), cts.Token);

        Assert.NotNull(connection);
        Assert.Equal(serverIdentity.Fingerprint, connection.PeerFingerprint);

        await connection.SendAsync(ControlMessage.Request("hello", "1", new HelloPayload(
            SlipstreamPorts.ProtocolVersion, clientIdentity.DeviceId,
            clientIdentity.DisplayName, clientIdentity.Fingerprint)), cts.Token);

        var reply = await connection.ReceiveAsync(cts.Token);

        Assert.Equal("hello.ok", reply!.Type);
        Assert.Equal("1", reply.Id);
        Assert.Equal(serverIdentity.DeviceId, reply.PayloadAs<HelloPayload>()!.DeviceId);

        await cts.CancelAsync();
        await SwallowAsync(running);
    }

    [Fact]
    public async Task Server_drops_a_connection_from_an_untrusted_fingerprint()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));

        var serverIdentity = DeviceIdentity.CreateNew("Server PC");
        var strangerIdentity = DeviceIdentity.CreateNew("Stranger");

        var serverPeers = new PairedPeerStore(_serverDir);
        serverPeers.Pair(new PairedPeer("someone-else", "a-different-fingerprint", "Other", DateTimeOffset.UnixEpoch));

        var strangerPeers = new PairedPeerStore(_clientDir);
        strangerPeers.Pair(new PairedPeer(serverIdentity.DeviceId, serverIdentity.Fingerprint, "Server PC", DateTimeOffset.UnixEpoch));

        var handled = false;

        await using var server = new ControlServer(serverIdentity, serverPeers, IPAddress.Loopback, port: 0);
        server.PeerConnected += (_, _) => { handled = true; return Task.CompletedTask; };

        var running = server.RunAsync(cts.Token);

        var client = new ControlClient(strangerIdentity, strangerPeers);

        // The TLS handshake may succeed; the server then drops it on the fingerprint check.
        try
        {
            await using var connection = await client.ConnectAsync(
                server.ListenEndPoint, TimeSpan.FromSeconds(3), cts.Token);

            if (connection is not null)
            {
                await connection.SendAsync(ControlMessage.Request("hello", "1"), cts.Token);
                Assert.Null(await connection.ReceiveAsync(cts.Token)); // stream closed
            }
        }
        catch (Exception) { /* an abrupt close is an acceptable outcome */ }

        Assert.False(handled);

        await cts.CancelAsync();
        await SwallowAsync(running);
    }

    private static async Task SwallowAsync(Task task)
    {
        try { await task; } catch (OperationCanceledException) { }
    }
}
