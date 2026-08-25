using System.Net;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;
using Slipstream.Core.Net;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingClientTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-pairclient-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(20));

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    [Fact]
    public async Task Connects_while_completely_unpaired()
    {
        // ConnectAsync refuses when unpaired. The pairing path must not.
        var serverIdentity = DeviceIdentity.CreateNew("Server PC");
        var clientIdentity = DeviceIdentity.CreateNew("Client Phone");

        var window = new PairingWindow();
        window.Open();

        await using var server = new ControlServer(
            serverIdentity, new PairedPeerStore(Path.Combine(_dir, "srv")), IPAddress.Loopback, 0, window);

        var reached = new TaskCompletionSource();
        server.PairingConnected += (_, _) => { reached.TrySetResult(); return Task.CompletedTask; };
        _ = server.RunAsync(_cts.Token);

        var client = new ControlClient(clientIdentity, new PairedPeerStore(Path.Combine(_dir, "cli")));

        await using var connection = await client.ConnectForPairingAsync(
            server.ListenEndPoint, TimeSpan.FromSeconds(10), _cts.Token);

        Assert.NotNull(connection);
        Assert.Equal(serverIdentity.Fingerprint, connection.PeerFingerprint);

        await reached.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);
    }

    [Fact]
    public async Task Reports_the_servers_real_certificate_fingerprint()
    {
        var serverIdentity = DeviceIdentity.CreateNew("Server PC");

        var window = new PairingWindow();
        window.Open();

        await using var server = new ControlServer(
            serverIdentity, new PairedPeerStore(Path.Combine(_dir, "srv2")), IPAddress.Loopback, 0, window);
        _ = server.RunAsync(_cts.Token);

        var client = new ControlClient(
            DeviceIdentity.CreateNew("Client"), new PairedPeerStore(Path.Combine(_dir, "cli2")));

        await using var connection = await client.ConnectForPairingAsync(
            server.ListenEndPoint, TimeSpan.FromSeconds(10), _cts.Token);

        // This is what the code is derived from — it must be the certificate, not a claim.
        Assert.Equal(serverIdentity.Fingerprint, connection!.PeerFingerprint);
    }

    [Fact]
    public async Task Refuses_a_non_local_endpoint()
    {
        var client = new ControlClient(
            DeviceIdentity.CreateNew("Client"), new PairedPeerStore(Path.Combine(_dir, "cli3")));

        await Assert.ThrowsAsync<NonLocalAddressException>(() =>
            client.ConnectForPairingAsync(
                new IPEndPoint(IPAddress.Parse("8.8.8.8"), 53321), TimeSpan.FromSeconds(2), _cts.Token));
    }

    [Fact]
    public async Task Returns_null_when_nothing_is_listening()
    {
        var client = new ControlClient(
            DeviceIdentity.CreateNew("Client"), new PairedPeerStore(Path.Combine(_dir, "cli4")));

        var connection = await client.ConnectForPairingAsync(
            new IPEndPoint(IPAddress.Loopback, 1), TimeSpan.FromMilliseconds(500), _cts.Token);

        Assert.Null(connection);
    }
}
