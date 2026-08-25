using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingAcceptPathTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-pairaccept-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(20));

    private readonly DeviceIdentity _server = DeviceIdentity.CreateNew("Server PC");
    private readonly DeviceIdentity _stranger = DeviceIdentity.CreateNew("Stranger");

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    /// <summary>Connects as an unpaired stranger: TLS, but no pin to check against.</summary>
    private async Task<ControlConnection?> ConnectUnpairedAsync(IPEndPoint endpoint)
    {
        var tcp = new TcpClient { NoDelay = true };
        try
        {
            await tcp.ConnectAsync(endpoint, _cts.Token);

            var stream = await PinnedTls.AuthenticateAsClientAsync(
                tcp.GetStream(), _stranger, acceptFingerprint: _ => true, _cts.Token);

            return new ControlConnection(stream, PinnedTls.FingerprintOf(stream), endpoint);
        }
        catch
        {
            tcp.Dispose();
            return null;
        }
    }

    [Fact]
    public async Task With_the_window_closed_an_unpaired_connection_is_still_dropped()
    {
        // Plan 1's guarantee, unchanged. This is the test that must never go green
        // for the wrong reason.
        var window = new PairingWindow(); // never opened
        var pairingRaised = false;
        var peerRaised = false;

        await using var server = new ControlServer(
            _server, new PairedPeerStore(_dir), IPAddress.Loopback, 0, window);

        server.PairingConnected += (_, _) => { pairingRaised = true; return Task.CompletedTask; };
        server.PeerConnected += (_, _) => { peerRaised = true; return Task.CompletedTask; };

        _ = server.RunAsync(_cts.Token);

        var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        if (connection is not null)
        {
            try
            {
                await connection.SendAsync(ControlMessage.Request("pair.offer", "1"), _cts.Token);
                Assert.Null(await connection.ReceiveAsync(_cts.Token)); // stream closed
            }
            catch (Exception) { /* an abrupt close is an acceptable outcome */ }
            finally
            {
                await connection.DisposeAsync();
            }
        }

        Assert.False(pairingRaised);
        Assert.False(peerRaised);
    }

    [Fact]
    public async Task With_no_window_supplied_at_all_an_unpaired_connection_is_dropped()
    {
        var pairingRaised = false;

        await using var server = new ControlServer(
            _server, new PairedPeerStore(_dir), IPAddress.Loopback, 0); // no window argument

        server.PairingConnected += (_, _) => { pairingRaised = true; return Task.CompletedTask; };
        _ = server.RunAsync(_cts.Token);

        var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        if (connection is not null) await connection.DisposeAsync();

        await Task.Delay(300, _cts.Token);
        Assert.False(pairingRaised);
    }

    [Fact]
    public async Task With_the_window_open_an_unpaired_connection_reaches_the_pairing_handler()
    {
        var window = new PairingWindow();
        window.Open();

        var pairingFingerprint = new TaskCompletionSource<string>();

        await using var server = new ControlServer(
            _server, new PairedPeerStore(_dir), IPAddress.Loopback, 0, window);

        server.PairingConnected += (connection, _) =>
        {
            pairingFingerprint.TrySetResult(connection.PeerFingerprint);
            return Task.CompletedTask;
        };

        _ = server.RunAsync(_cts.Token);

        await using var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        Assert.NotNull(connection);

        var seen = await pairingFingerprint.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);
        Assert.Equal(_stranger.Fingerprint, seen);
    }

    [Fact]
    public async Task An_unpaired_connection_never_reaches_the_normal_peer_handler()
    {
        // The restricted path must not leak into browse/transfer.
        var window = new PairingWindow();
        window.Open();

        var peerRaised = false;
        var pairingRaised = new TaskCompletionSource();

        await using var server = new ControlServer(
            _server, new PairedPeerStore(_dir), IPAddress.Loopback, 0, window);

        server.PeerConnected += (_, _) => { peerRaised = true; return Task.CompletedTask; };
        server.PairingConnected += (_, _) => { pairingRaised.TrySetResult(); return Task.CompletedTask; };

        _ = server.RunAsync(_cts.Token);

        await using var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        await pairingRaised.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);

        Assert.False(peerRaised);
    }

    [Fact]
    public async Task An_already_paired_peer_still_reaches_the_normal_handler_while_the_window_is_open()
    {
        var window = new PairingWindow();
        window.Open();

        var peers = new PairedPeerStore(_dir);
        peers.Pair(new PairedPeer(_stranger.DeviceId, _stranger.Fingerprint, "Stranger", DateTimeOffset.UtcNow));

        var peerRaised = new TaskCompletionSource();
        var pairingRaised = false;

        await using var server = new ControlServer(_server, peers, IPAddress.Loopback, 0, window);

        server.PeerConnected += (_, _) => { peerRaised.TrySetResult(); return Task.CompletedTask; };
        server.PairingConnected += (_, _) => { pairingRaised = true; return Task.CompletedTask; };

        _ = server.RunAsync(_cts.Token);

        await using var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        await peerRaised.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);

        Assert.False(pairingRaised);
    }

    [Fact]
    public async Task An_expired_window_drops_unpaired_connections_again()
    {
        var window = new PairingWindow();
        window.Open();
        window.Close(); // simulates expiry from the accept path's point of view

        var pairingRaised = false;

        await using var server = new ControlServer(
            _server, new PairedPeerStore(_dir), IPAddress.Loopback, 0, window);

        server.PairingConnected += (_, _) => { pairingRaised = true; return Task.CompletedTask; };
        _ = server.RunAsync(_cts.Token);

        var connection = await ConnectUnpairedAsync(server.ListenEndPoint);
        if (connection is not null) await connection.DisposeAsync();

        await Task.Delay(300, _cts.Token);
        Assert.False(pairingRaised);
    }
}
