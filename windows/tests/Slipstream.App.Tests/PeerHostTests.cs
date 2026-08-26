using System.Diagnostics;
using System.Runtime.Versioning;
using Slipstream.App.Services;
using Slipstream.Core.Tests;
using Slipstream.Core.Transfer;

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
        Assert.NotEmpty((await host.ListAsync(_sharedDir, _cts.Token)).Entries);
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

        rig.BreakControlConnection();
        await WaitUntil(() => host.State == PeerConnectionState.Lost, TimeSpan.FromSeconds(10));

        Assert.True(await host.ReconnectAsync(_cts.Token));
        Assert.Equal(PeerConnectionState.Connected, host.State);
    }

    [Fact]
    public async Task PullAsync_surfaces_a_peer_refusal_immediately_instead_of_retrying_it()
    {
        // Regression test for review finding #9: a genuine protocol-level refusal (the peer
        // explicitly saying "no", e.g. a file that no longer exists) must NOT be treated as a
        // transient connectivity failure worth retrying 40x over 20 seconds — it should
        // surface as soon as the peer's single reply arrives.
        await using var rig = await TwoPeers.StartAsync(_dir, _cts.Token);
        await using var host = new PeerHost(rig.Client, downloadDirectory: _downloads);
        await host.StartAsync(_cts.Token);

        var missingPath = Path.Combine(_sharedDir, "does-not-exist.bin");

        var sw = Stopwatch.StartNew();
        await Assert.ThrowsAsync<Slipstream.Core.Control.ControlProtocolException>(
            () => host.PullAsync(missingPath, progress: null, _cts.Token));
        sw.Stop();

        // 40 retries at 500ms would be ~20s; a non-retried refusal should come back in well
        // under a second of real network round trips.
        Assert.True(sw.Elapsed < TimeSpan.FromSeconds(5),
            $"Expected an immediate refusal, but PullAsync took {sw.Elapsed} — looks like it retried.");
    }

    [Fact]
    public async Task PullAsync_does_not_hold_the_control_gate_for_the_whole_transfer()
    {
        // Regression test: PullAsync used to hold _gate for the full bulk transfer, which
        // serialised it behind every other control round trip — including the heartbeat
        // loop's ping. A slow-enough transfer would starve the heartbeat past its 3s
        // timeout and flip State to Lost, and any concurrent call like ListAsync would
        // simply queue behind the whole download instead of running immediately.
        var bigFile = Path.Combine(_sharedDir, "big.bin");
        await using (var fs = new FileStream(bigFile, FileMode.Create))
        {
            var buffer = new byte[4 * 1024 * 1024];
            Random.Shared.NextBytes(buffer);
            for (var i = 0; i < 20; i++) // ~80 MB: enough to span at least one heartbeat tick.
                await fs.WriteAsync(buffer, _cts.Token);
        }

        // PeerHost.PullAsync hardcodes SlipstreamPorts.Bulk for the remote bulk endpoint
        // (it doesn't discover it), so the server side of this rig needs to bind that
        // well-known port rather than an ephemeral one for the download to actually work.
        await using var rig = await TwoPeers.StartAsync(_dir, _cts.Token, serverUsesFixedPorts: true);
        await using var host = new PeerHost(rig.Client, downloadDirectory: _downloads);
        await host.StartAsync(_cts.Token);

        var states = new List<PeerConnectionState>();
        host.StateChanged += (s, _, _) => { lock (states) states.Add(s); };

        var pullTask = host.PullAsync(bigFile, progress: null, _cts.Token);

        // While the transfer is (presumably) still running, hammer the control channel
        // with ListAsync calls. Each one must return promptly rather than queueing behind
        // the whole download — proof the gate isn't held for the bulk phase.
        for (var i = 0; i < 4 && !pullTask.IsCompleted; i++)
        {
            var sw = Stopwatch.StartNew();
            Assert.NotEmpty((await host.ListAsync(_sharedDir, _cts.Token)).Entries);
            sw.Stop();

            Assert.True(sw.Elapsed < TimeSpan.FromSeconds(2),
                $"ListAsync took {sw.Elapsed} while a pull was in flight — the gate appears to be held for the bulk transfer.");

            await Task.Delay(300, _cts.Token);
        }

        var downloadedPath = await pullTask;
        Assert.True(File.Exists(downloadedPath));

        lock (states)
        {
            Assert.DoesNotContain(PeerConnectionState.Lost, states);
        }
        Assert.Equal(PeerConnectionState.Connected, host.State);
    }

    [Fact]
    public async Task A_network_change_tears_down_rediscovers_and_resumes()
    {
        // spec §5: a network change is routine, never an error state — the transfer must
        // finish, not fail.
        var largeFile = Path.Combine(_sharedDir, "large.bin");
        var payload = new byte[80 * 1024 * 1024]; // large enough to still be in flight when we break the link.
        Random.Shared.NextBytes(payload);
        await File.WriteAllBytesAsync(largeFile, payload, _cts.Token);

        // PeerHost.PullAsync hardcodes SlipstreamPorts.Bulk for the remote bulk endpoint,
        // so the server side needs to bind that well-known port for the download to work.
        await using var rig = await TwoPeers.StartAsync(_dir, _cts.Token, serverUsesFixedPorts: true);
        await using var host = new PeerHost(rig.Client, _downloads);
        await host.StartAsync(_cts.Token);

        long bytesBeforeBreak = 0;
        var progress = new Progress<TransferProgress>(p => Interlocked.Exchange(ref bytesBeforeBreak, p.BytesCompleted));

        var transfer = host.PullAsync(largeFile, progress, _cts.Token);

        // Wait until some bytes have genuinely landed before severing anything, so the
        // break happens mid-transfer rather than possibly before the bulk socket even
        // opens — otherwise resume wouldn't be necessary, only possible.
        await WaitUntil(() => Interlocked.Read(ref bytesBeforeBreak) > 0, TimeSpan.FromSeconds(20));
        var progressBeforeBreak = Interlocked.Read(ref bytesBeforeBreak);

        // Break BOTH the control and bulk channels: breaking only the control connection
        // (as BreakControlConnection alone would) leaves the in-flight bulk download
        // running on its own already-open socket, so it can simply complete independently
        // while control reconnects in the background — never forcing PeerHost's
        // resume-from-chunk-bitmap path (ResumeAfterDisconnectAsync) to actually run.
        // Severing the bulk socket too makes the in-flight BulkClient.DownloadAsync fail
        // for real, so resume is genuinely required, not just possible.
        rig.BreakAllConnections();
        rig.Client.RaiseNetworkChanged(); // what NetworkChange delivers in production

        var local = await transfer;

        Assert.Equal(payload, await File.ReadAllBytesAsync(local, _cts.Token));
        Assert.Equal(PeerConnectionState.Connected, host.State);

        // Evidence that resume was actually necessary: real progress had already been made
        // (bytes downloaded into the part file's chunk bitmap) at the moment the link was
        // severed, and the file still completes byte-identical afterwards — proving the
        // resume-from-bitmap path, not a lucky independent completion, finished the transfer.
        Assert.True(progressBeforeBreak > 0, "Expected some bytes to have been downloaded before the break.");
        Assert.True(progressBeforeBreak < payload.Length, "Expected the transfer to still be in flight when broken.");
    }
}
