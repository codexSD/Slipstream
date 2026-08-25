using System.Net;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingCoordinatorTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-paircoord-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(30));

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    /// <summary>Two cold, never-paired instances wired together over loopback.</summary>
    private sealed record Rig(
        DeviceIdentity ServerIdentity, PairedPeerStore ServerPeers, PairingWindow ServerWindow,
        PairingCoordinator ServerCoordinator, ControlServer Server,
        DeviceIdentity ClientIdentity, PairedPeerStore ClientPeers, PairingWindow ClientWindow,
        PairingCoordinator ClientCoordinator, ControlClient Client);

    private Rig BuildRig()
    {
        var serverIdentity = DeviceIdentity.CreateNew("Server PC");
        var clientIdentity = DeviceIdentity.CreateNew("Client Phone");

        var serverPeers = new PairedPeerStore(Path.Combine(_dir, "srv"));
        var clientPeers = new PairedPeerStore(Path.Combine(_dir, "cli"));

        var serverWindow = new PairingWindow();
        var clientWindow = new PairingWindow();
        serverWindow.Open();
        clientWindow.Open();

        var serverClient = new ControlClient(serverIdentity, serverPeers);
        var client = new ControlClient(clientIdentity, clientPeers);

        var server = new ControlServer(
            serverIdentity, serverPeers, IPAddress.Loopback, 0, serverWindow);

        return new Rig(
            serverIdentity, serverPeers, serverWindow,
            new PairingCoordinator(serverIdentity, serverPeers, serverClient, serverWindow), server,
            clientIdentity, clientPeers, clientWindow,
            new PairingCoordinator(clientIdentity, clientPeers, client, clientWindow), client);
    }

    private static Func<string, CancellationToken, Task<bool>> Accepts(List<string> seen) =>
        (code, _) => { lock (seen) seen.Add(code); return Task.FromResult(true); };

    private static Func<string, CancellationToken, Task<bool>> Declines() =>
        (_, _) => Task.FromResult(false);

    /// <summary>Runs both halves and returns (serverResult, clientResult).</summary>
    private async Task<(PairedPeer?, PairedPeer?)> RunAsync(
        Rig rig,
        Func<string, CancellationToken, Task<bool>> serverConfirm,
        Func<string, CancellationToken, Task<bool>> clientConfirm)
    {
        PairedPeer? serverResult = null;

        var accepted = new TaskCompletionSource();
        rig.Server.PairingConnected += async (connection, token) =>
        {
            accepted.TrySetResult();
            serverResult = await rig.ServerCoordinator.PairAsync(
                connection, isInitiator: false, serverConfirm, null, token);
        };

        _ = rig.Server.RunAsync(_cts.Token);

        await using var connection = await rig.Client.ConnectForPairingAsync(
            rig.Server.ListenEndPoint, TimeSpan.FromSeconds(10), _cts.Token);

        var clientResult = await rig.ClientCoordinator.PairAsync(
            connection!, isInitiator: true, clientConfirm, null, _cts.Token);

        await accepted.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);
        await Task.Delay(500, _cts.Token); // let the server half settle

        return (serverResult, clientResult);
    }

    [Fact]
    public async Task Two_cold_devices_reach_a_mutual_paired_state()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        var (serverResult, clientResult) = await RunAsync(rig, Accepts([]), Accepts([]));

        Assert.NotNull(serverResult);
        Assert.NotNull(clientResult);

        Assert.True(rig.ServerPeers.IsPaired);
        Assert.True(rig.ClientPeers.IsPaired);

        Assert.True(rig.ServerPeers.Trusts(rig.ClientIdentity.Fingerprint));
        Assert.True(rig.ClientPeers.Trusts(rig.ServerIdentity.Fingerprint));
    }

    [Fact]
    public async Task Both_users_are_shown_the_same_six_digit_code()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        var serverCodes = new List<string>();
        var clientCodes = new List<string>();

        await RunAsync(rig, Accepts(serverCodes), Accepts(clientCodes));

        Assert.Single(serverCodes);
        Assert.Single(clientCodes);
        Assert.Equal(serverCodes[0], clientCodes[0]);
        Assert.Matches("^[0-9]{6}$", serverCodes[0]);
    }

    [Fact]
    public async Task The_persisted_peer_carries_the_other_devices_display_name()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        await RunAsync(rig, Accepts([]), Accepts([]));

        Assert.Equal("Client Phone", rig.ServerPeers.Current!.DisplayName);
        Assert.Equal("Server PC", rig.ClientPeers.Current!.DisplayName);
    }

    [Fact]
    public async Task Neither_side_pairs_when_one_user_declines()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        var (serverResult, clientResult) = await RunAsync(rig, Declines(), Accepts([]));

        Assert.Null(serverResult);
        Assert.Null(clientResult);
        Assert.False(rig.ServerPeers.IsPaired);
        Assert.False(rig.ClientPeers.IsPaired);
    }

    [Fact]
    public async Task Neither_side_pairs_when_the_initiator_declines()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        var (serverResult, clientResult) = await RunAsync(rig, Accepts([]), Declines());

        Assert.Null(serverResult);
        Assert.Null(clientResult);
        Assert.False(rig.ServerPeers.IsPaired);
        Assert.False(rig.ClientPeers.IsPaired);
    }

    [Fact]
    public async Task A_successful_pairing_closes_both_windows()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        await RunAsync(rig, Accepts([]), Accepts([]));

        Assert.False(rig.ServerWindow.IsOpen);
        Assert.False(rig.ClientWindow.IsOpen);
    }

    [Fact]
    public async Task After_pairing_the_normal_pinned_connect_path_works()
    {
        // The real acceptance criterion: pairing is only useful if it produces a
        // state the rest of the app can actually use.
        var rig = BuildRig();
        await using var _ = rig.Server;

        await RunAsync(rig, Accepts([]), Accepts([]));

        var reached = new TaskCompletionSource();
        rig.Server.PeerConnected += (_, _) => { reached.TrySetResult(); return Task.CompletedTask; };

        await using var connection = await rig.Client.ConnectAsync(
            rig.Server.ListenEndPoint, TimeSpan.FromSeconds(10), _cts.Token);

        Assert.NotNull(connection);
        await reached.Task.WaitAsync(TimeSpan.FromSeconds(10), _cts.Token);
    }

    [Fact]
    public async Task Pairing_replaces_an_existing_peer()
    {
        var rig = BuildRig();
        await using var _ = rig.Server;

        rig.ClientPeers.Pair(new PairedPeer("old-device", "oldfingerprint", "Old PC", DateTimeOffset.UtcNow));

        await RunAsync(rig, Accepts([]), Accepts([]));

        Assert.False(rig.ClientPeers.Trusts("oldfingerprint"));
        Assert.True(rig.ClientPeers.Trusts(rig.ServerIdentity.Fingerprint));
    }
}
