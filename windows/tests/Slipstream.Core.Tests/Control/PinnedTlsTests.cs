using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests.Control;

public class PinnedTlsTests
{
    /// <summary>Starts a TLS server on loopback and returns its endpoint.</summary>
    private static (TcpListener Listener, Task Serving) StartServer(
        DeviceIdentity identity,
        Func<ControlConnection, Task> handle,
        CancellationToken cancellationToken)
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();

        var serving = Task.Run(async () =>
        {
            try
            {
                using var client = await listener.AcceptTcpClientAsync(cancellationToken);
                var stream = await PinnedTls.AuthenticateAsServerAsync(
                    client.GetStream(), identity, cancellationToken);

                await using var connection = new ControlConnection(
                    stream, PinnedTls.FingerprintOf(stream),
                    (IPEndPoint)client.Client.RemoteEndPoint!);

                await handle(connection);
            }
            catch (Exception) { /* the test asserts on the client side */ }
        }, cancellationToken);

        return (listener, serving);
    }

    [Fact]
    public async Task Client_connects_when_the_server_fingerprint_is_trusted()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));

        var serverIdentity = DeviceIdentity.CreateNew("Server");
        var clientIdentity = DeviceIdentity.CreateNew("Client");

        var (listener, _) = StartServer(serverIdentity, async connection =>
        {
            await connection.SendAsync(ControlMessage.Event("hello.ok"), cts.Token);
        }, cts.Token);

        try
        {
            var endpoint = (IPEndPoint)listener.LocalEndpoint;

            using var tcp = new TcpClient();
            await tcp.ConnectAsync(endpoint, cts.Token);

            await using var stream = await PinnedTls.AuthenticateAsClientAsync(
                tcp.GetStream(), clientIdentity,
                fingerprint => fingerprint == serverIdentity.Fingerprint, cts.Token);

            Assert.Equal(serverIdentity.Fingerprint, PinnedTls.FingerprintOf(stream));

            var codec = new JsonLineCodec(stream);
            var message = await codec.ReadAsync(cts.Token);
            Assert.Equal("hello.ok", message!.Type);
        }
        finally
        {
            listener.Stop();
        }
    }

    [Fact]
    public async Task Client_refuses_a_server_whose_fingerprint_is_not_pinned()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));

        var serverIdentity = DeviceIdentity.CreateNew("Server");
        var clientIdentity = DeviceIdentity.CreateNew("Client");

        var (listener, _) = StartServer(serverIdentity, _ => Task.CompletedTask, cts.Token);

        try
        {
            using var tcp = new TcpClient();
            await tcp.ConnectAsync((IPEndPoint)listener.LocalEndpoint, cts.Token);

            await Assert.ThrowsAnyAsync<Exception>(() =>
                PinnedTls.AuthenticateAsClientAsync(
                    tcp.GetStream(), clientIdentity,
                    _ => false, // trust nothing
                    cts.Token));
        }
        finally
        {
            listener.Stop();
        }
    }

    [Fact]
    public async Task Probe_returns_the_peer_for_a_trusted_endpoint()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var dir = Directory.CreateTempSubdirectory("slipstream-probe-").FullName;

        try
        {
            var serverIdentity = DeviceIdentity.CreateNew("Server");
            var clientIdentity = DeviceIdentity.CreateNew("Client");

            var peers = new PairedPeerStore(dir);
            peers.Pair(new PairedPeer("server-device", serverIdentity.Fingerprint, "Server", DateTimeOffset.UnixEpoch));

            var (listener, _) = StartServer(serverIdentity, async connection =>
            {
                await connection.ReceiveAsync(cts.Token);
                await Task.Delay(Timeout.Infinite, cts.Token);
            }, cts.Token);

            try
            {
                var probe = new ControlClient(clientIdentity, peers).CreateProbe(TimeSpan.FromSeconds(5));
                var found = await probe((IPEndPoint)listener.LocalEndpoint, cts.Token);

                Assert.NotNull(found);
                Assert.Equal("server-device", found.Peer.DeviceId);
            }
            finally
            {
                listener.Stop();
            }
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    [Fact]
    public async Task Probe_returns_null_for_an_untrusted_endpoint()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var dir = Directory.CreateTempSubdirectory("slipstream-probe2-").FullName;

        try
        {
            var serverIdentity = DeviceIdentity.CreateNew("Server");
            var clientIdentity = DeviceIdentity.CreateNew("Client");

            var peers = new PairedPeerStore(dir);
            peers.Pair(new PairedPeer("other", "not-the-server-fingerprint", "Other", DateTimeOffset.UnixEpoch));

            var (listener, _) = StartServer(serverIdentity, _ => Task.CompletedTask, cts.Token);

            try
            {
                var probe = new ControlClient(clientIdentity, peers).CreateProbe(TimeSpan.FromSeconds(5));
                Assert.Null(await probe((IPEndPoint)listener.LocalEndpoint, cts.Token));
            }
            finally
            {
                listener.Stop();
            }
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    [Fact]
    public async Task Probe_returns_null_for_a_closed_port_without_throwing()
    {
        var dir = Directory.CreateTempSubdirectory("slipstream-probe3-").FullName;

        try
        {
            var peers = new PairedPeerStore(dir);
            peers.Pair(new PairedPeer("x", "abcd", "X", DateTimeOffset.UnixEpoch));

            var probe = new ControlClient(DeviceIdentity.CreateNew("Client"), peers)
                .CreateProbe(TimeSpan.FromMilliseconds(500));

            // Port 1 on loopback: nothing listening.
            Assert.Null(await probe(new IPEndPoint(IPAddress.Loopback, 1), CancellationToken.None));
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    [Fact]
    public async Task Probe_refuses_a_non_local_endpoint_without_connecting()
    {
        var dir = Directory.CreateTempSubdirectory("slipstream-probe4-").FullName;

        try
        {
            var peers = new PairedPeerStore(dir);
            peers.Pair(new PairedPeer("x", "abcd", "X", DateTimeOffset.UnixEpoch));

            var probe = new ControlClient(DeviceIdentity.CreateNew("Client"), peers)
                .CreateProbe(TimeSpan.FromSeconds(5));

            Assert.Null(await probe(new IPEndPoint(IPAddress.Parse("8.8.8.8"), 53321), CancellationToken.None));
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }
}
