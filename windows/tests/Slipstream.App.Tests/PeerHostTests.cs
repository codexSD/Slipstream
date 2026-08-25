using System.Runtime.Versioning;
using Slipstream.App.Services;
using Slipstream.Core.Tests;

namespace Slipstream.App.Tests;

[SupportedOSPlatform("windows")]
public class PeerHostTests : IAsyncLifetime
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-peerhost-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(60));

    private string _downloads = null!;
    private string _sharedDir = null!;

    public async Task InitializeAsync()
    {
        _downloads = Path.Combine(_dir, "downloads");
        _sharedDir = Path.Combine(_dir, "shared");
        Directory.CreateDirectory(_sharedDir);
        await File.WriteAllTextAsync(Path.Combine(_sharedDir, "hello.txt"), "hi", _cts.Token);
    }

    public Task DisposeAsync()
    {
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
        return Task.CompletedTask;
    }

    private static async Task WaitUntil(Func<bool> condition, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (!condition())
        {
            if (DateTime.UtcNow > deadline)
                throw new TimeoutException("Condition was not met in time.");
            await Task.Delay(50);
        }
    }

    [Fact]
    public async Task Reaches_Connected_and_lists_the_peers_files()
    {
        await using var rig = await TwoPeers.StartAsync(_dir, _cts.Token);
        await using var host = new PeerHost(rig.Client, downloadDirectory: _downloads);

        await host.StartAsync(_cts.Token);

        Assert.Equal(PeerConnectionState.Connected, host.State);
        Assert.NotEmpty(await host.ListAsync(_sharedDir, _cts.Token));
    }

    [Fact]
    public async Task Reports_Lost_then_recovers_on_reconnect()
    {
        // spec §5: a network switch is routine, never an error state.
        await using var rig = await TwoPeers.StartAsync(_dir, _cts.Token);
        await using var host = new PeerHost(rig.Client, _downloads);
        await host.StartAsync(_cts.Token);

        var states = new List<PeerConnectionState>();
        host.StateChanged += (s, _, _) => { lock (states) states.Add(s); };

        await rig.BreakControlConnectionAsync();
        await WaitUntil(() => host.State == PeerConnectionState.Lost, TimeSpan.FromSeconds(10));

        Assert.True(await host.ReconnectAsync(_cts.Token));
        Assert.Equal(PeerConnectionState.Connected, host.State);
    }
}
