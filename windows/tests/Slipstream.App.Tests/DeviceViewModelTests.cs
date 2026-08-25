using Slipstream.App.Pages;
using Slipstream.App.Services;
using Slipstream.App.Tests.Fakes;
using Slipstream.Core.Files;
using Slipstream.Core.Identity;
using Slipstream.Core.Transfer;
using Xunit;

namespace Slipstream.App.Tests;

/// <summary>
/// Tests the plain C# <see cref="DeviceViewModel"/> directly — no UI thread needed, since
/// WinUI controls can't be instantiated headless under bare `dotnet test` (confirmed by
/// Tasks 5/7/8's COMException findings).
/// </summary>
public class DeviceViewModelTests
{
    [Fact]
    public void Peer_state_stat_reflects_connection_state()
    {
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        host.RaiseState(PeerConnectionState.Connected, "Kai's iPhone");

        Assert.Equal("Connected", vm.PeerStateText);
    }

    [Fact]
    public void Peer_state_stat_shows_not_connected_when_idle()
    {
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        Assert.Equal("Not connected", vm.PeerStateText);
    }

    [Fact]
    public void Link_rate_and_transferred_today_are_resting_placeholders_absent_a_live_source()
    {
        // Core does not currently expose a live link-rate or per-day-transferred-bytes
        // stream to IPeerHost — that data belongs to Task 12's TransferQueue (live rate,
        // via TransferProgress) and a future history/stats aggregation. Until that wiring
        // exists, DeviceViewModel shows a clearly resting value rather than fabricating one.
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        host.RaiseState(PeerConnectionState.Connected, "Kai's iPhone");

        Assert.Equal("—", vm.LinkRateText);
        Assert.Equal("—", vm.TransferredTodayText);
    }

    [Fact]
    public void Transferred_today_sums_todays_completed_history_entries()
    {
        // Regression coverage for review finding #12: TransferredTodayText IS computable once
        // a HistoryStore is supplied — unlike LinkRateText, which genuinely has no data source.
        var store = new HistoryStore(Path.Combine(Path.GetTempPath(), $"slipstream-test-{Guid.NewGuid():N}.json"));
        var today = DateTimeOffset.UtcNow;
        var yesterday = today.AddDays(-1);

        store.Add(new HistoryEntry("/a.mp4", "C:\\a.mp4", 1_000_000, TransferStatus.Complete, today));
        store.Add(new HistoryEntry("/b.mp4", "C:\\b.mp4", 500_000, TransferStatus.Complete, today));
        store.Add(new HistoryEntry("/c.mp4", "C:\\c.mp4", 9_999_999, TransferStatus.Failed, today)); // not Complete — excluded
        store.Add(new HistoryEntry("/d.mp4", "C:\\d.mp4", 9_999_999, TransferStatus.Complete, yesterday)); // not today — excluded

        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host, historyStore: store);

        host.RaiseState(PeerConnectionState.Connected, "Kai's iPhone");

        Assert.Equal("1.4 MB", vm.TransferredTodayText);
    }

    [Fact]
    public void Hero_metric_shows_resting_value_when_no_live_transfer_is_in_progress()
    {
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        host.RaiseState(PeerConnectionState.Connected, "Kai's iPhone");

        Assert.Equal("—", vm.HeroRateText);
    }

    [Fact]
    public async Task Hero_rate_updates_when_TransferQueue_reports_progress_from_a_background_thread()
    {
        // TransferQueue.ItemUpdated (see TransferQueue.RunAsync) fires from whatever thread
        // is running the transfer's Task, never the UI thread. DeviceViewModel's
        // OnTransferUpdated must marshal through RunOnUiThread (matching TransfersViewModel's
        // established pattern) before touching HeroRateText, an observable UI-bound property.
        // A real DispatcherQueue can't be constructed headless under `dotnet test` (Tasks
        // 5/7/8's COMException findings), so this test can't observe the marshal itself — with
        // no dispatcher supplied, DeviceViewModel falls back to DispatcherQueue.GetForCurrentThread(),
        // which is null off a real UI thread, and RunOnUiThread's null-dispatcher branch runs the
        // action inline (see TransfersViewModel.RunOnUiThread, the identical, already-shipped
        // pattern this mirrors). What this test does verify: the callback reaches
        // DeviceViewModel correctly and safely from a genuine background thread — i.e. nothing
        // about the new wiring assumes it's already on the UI thread — and that HeroRateText
        // ends up reflecting the reported rate.
        var host = new BlockingPullPeerHost();
        var queue = new TransferQueue(host, maxConcurrent: 1);
        var vm = new DeviceViewModel(host, queue);

        queue.Enqueue("/DCIM/100APPLE/IMG_0001.HEIC");
        await host.RunningOnBackgroundThread.Task.WaitAsync(TimeSpan.FromSeconds(5));

        host.ReportProgressFromBackgroundThread(new TransferProgress(Guid.Empty, 1L << 20, 4L << 20, 2 * 1024 * 1024));

        // Progress<T> marshals its callback (via a captured SynchronizationContext, or the
        // ThreadPool absent one) rather than invoking it synchronously on Report(), so poll
        // briefly instead of asserting immediately after the call returns.
        var expected = Slipstream.App.Services.TransferItem.FormatRate(2 * 1024 * 1024);
        var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(5);
        while (vm.HeroRateText != expected && DateTime.UtcNow < deadline)
            await Task.Delay(10);

        Assert.Equal(expected, vm.HeroRateText);

        host.Release();
        await queue.WaitForIdleAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(5));
    }

    /// <summary>Test double whose <see cref="PullAsync"/> blocks on a background <see cref="Task"/>
    /// until <see cref="Release"/> is called, so tests can report progress mid-transfer from a
    /// genuine non-UI thread (proving DeviceViewModel's marshal path is exercised, not just
    /// compiled).</summary>
    private sealed class BlockingPullPeerHost : IPeerHost
    {
        private readonly TaskCompletionSource _release = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private IProgress<TransferProgress>? _progress;

        public TaskCompletionSource RunningOnBackgroundThread { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public PeerConnectionState State => PeerConnectionState.Connected;
        public string? PeerName => "Fake Peer";
        public string? Band => null;
        public string? DiscoveryStrategy => null;
        public TimeSpan? DiscoveryElapsed => null;

        public bool IsDiscoveryPaused { get; private set; }
        public void PauseDiscovery() => IsDiscoveryPaused = true;
        public void ResumeDiscovery() => IsDiscoveryPaused = false;

        public event Action<PeerConnectionState, string?, string?>? StateChanged { add { } remove { } }

        public Task StartAsync(CancellationToken ct) => Task.CompletedTask;
        public Task<bool> ReconnectAsync(CancellationToken ct) => Task.FromResult(true);
        public Task<ListResult> ListAsync(string path, CancellationToken ct) => Task.FromResult(new ListResult(path, [], false));
        public Task StreamAsync(string remotePath, CancellationToken ct) => Task.CompletedTask;
        public Task SendClipboardAsync(string text, CancellationToken ct) => Task.CompletedTask;
        public string? GetThumbnailUrl(string thumbnailToken) => null;

        public Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>> confirm, CancellationToken ct)
            => Task.FromResult<PairedPeer?>(null);

        public async Task<string> PullAsync(string remotePath, IProgress<TransferProgress>? progress, CancellationToken ct)
        {
            _progress = progress;
            // Runs on a ThreadPool thread, never the caller's — same as the real PullAsync
            // implementation, and definitely never the (nonexistent, in this test) UI thread.
            await Task.Run(() =>
            {
                Assert.False(RunningOnBackgroundThread.Task.IsCompleted);
                RunningOnBackgroundThread.SetResult();
                _release.Task.Wait();
            });
            return remotePath;
        }

        /// <summary>Invokes the progress callback from the calling (background) thread, exactly
        /// as Core's real progress reporting does inside TransferQueue.RunAsync.</summary>
        public void ReportProgressFromBackgroundThread(TransferProgress progress)
            => _progress?.Report(progress);

        public void Release() => _release.TrySetResult();
    }

    [Fact]
    public async Task Peer_state_stat_updates_safely_when_StateChanged_fires_from_a_background_thread()
    {
        // Task 16: PeerHost's NetworkChanged-driven reconnect loop now raises StateChanged
        // from its own background Task far more often than the old one-shot connect path
        // did. OnPeerStateChanged must marshal through RunOnUiThread (same as
        // OnTransferUpdated above) before touching PeerStateText/DiscoverySummaryText,
        // rather than mutating those observable properties straight off that thread.
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        await Task.Run(() => host.RaiseState(PeerConnectionState.Lost, null));

        var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(5);
        while (vm.PeerStateText != "Connection lost" && DateTime.UtcNow < deadline)
            await Task.Delay(10);

        Assert.Equal("Connection lost", vm.PeerStateText);
    }

    [Fact]
    public void Discovery_detail_lists_all_four_strategies()
    {
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        Assert.Equal(4, vm.DiscoveryStrategies.Count);
        Assert.Contains(vm.DiscoveryStrategies, s => s.Code == "S1" && s.Name == "Cached endpoint");
        Assert.Contains(vm.DiscoveryStrategies, s => s.Code == "S2" && s.Name == "Gateway probe");
        Assert.Contains(vm.DiscoveryStrategies, s => s.Code == "S3" && s.Name == "Multicast");
        Assert.Contains(vm.DiscoveryStrategies, s => s.Code == "S4" && s.Name == "Subnet sweep");
    }

    [Fact]
    public void Discovery_detail_names_the_winner_and_elapsed_time_once_known()
    {
        var host = new FakePeerHost();
        host.RaiseDiscovery("gateway-probe", TimeSpan.FromMilliseconds(1230));
        var vm = new DeviceViewModel(host);

        host.RaiseState(PeerConnectionState.Connected, "Kai's iPhone");

        var winner = Assert.Single(vm.DiscoveryStrategies, s => s.IsWinner);
        Assert.Equal("S2", winner.Code);
        Assert.Equal("Gateway probe", winner.Name);
        Assert.Equal("Connected via gateway probe in 1.2s", vm.DiscoverySummaryText);
    }

    [Fact]
    public void Discovery_detail_has_no_winner_before_any_discovery_completes()
    {
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        Assert.DoesNotContain(vm.DiscoveryStrategies, s => s.IsWinner);
        Assert.Equal("Not yet connected.", vm.DiscoverySummaryText);
    }
}
