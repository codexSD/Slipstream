using Slipstream.App.Pages;
using Slipstream.App.Services;

namespace Slipstream.App.Tests;

/// <summary>
/// Task 13: HistoryViewModel wraps a HistoryStore for display, computing per-row
/// reveal-in-folder availability and wiring "run again" back into a TransferQueue.
/// </summary>
public class HistoryViewModelTests : IDisposable
{
    private readonly string _dir;
    private readonly string _path;

    public HistoryViewModelTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "slipstream-history-vm-tests-" + Guid.NewGuid());
        Directory.CreateDirectory(_dir);
        _path = Path.Combine(_dir, "history.json");
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { /* best effort */ }
    }

    [Fact]
    public void Reveal_is_disabled_when_the_local_file_no_longer_exists()
    {
        var store = new HistoryStore(_path);
        var missingPath = Path.Combine(_dir, "gone.bin");
        store.Add(new HistoryEntry("/gone.bin", missingPath, 10, TransferStatus.Complete, DateTimeOffset.UtcNow));

        var host = new Slipstream.App.Tests.Fakes.FakePeerHost();
        var queue = new TransferQueue(host, maxConcurrent: 1);
        var vm = new HistoryViewModel(store, queue);

        var row = Assert.Single(vm.Items);
        Assert.False(row.CanReveal);
    }

    [Fact]
    public void Reveal_is_enabled_when_the_local_file_still_exists()
    {
        var store = new HistoryStore(_path);
        var existingPath = Path.Combine(_dir, "present.bin");
        File.WriteAllText(existingPath, "data");
        store.Add(new HistoryEntry("/present.bin", existingPath, 4, TransferStatus.Complete, DateTimeOffset.UtcNow));

        var host = new Slipstream.App.Tests.Fakes.FakePeerHost();
        var queue = new TransferQueue(host, maxConcurrent: 1);
        var vm = new HistoryViewModel(store, queue);

        var row = Assert.Single(vm.Items);
        Assert.True(row.CanReveal);
    }

    [Fact]
    public async Task Run_again_re_enqueues_the_entrys_remote_path()
    {
        var store = new HistoryStore(_path);
        store.Add(new HistoryEntry("/DCIM/100APPLE/IMG_0002.HEIC", "C:/local/IMG_0002.HEIC", 10, TransferStatus.Complete, DateTimeOffset.UtcNow));

        var host = new Slipstream.App.Tests.Fakes.FakePeerHost();
        var queue = new TransferQueue(host, maxConcurrent: 1);
        var vm = new HistoryViewModel(store, queue);

        var row = Assert.Single(vm.Items);
        vm.RunAgainCommand.Execute(row);

        await queue.WaitForIdleAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Contains(queue.Completed, t => t.Path == "/DCIM/100APPLE/IMG_0002.HEIC");
    }

    [Fact]
    public async Task Items_reflects_a_transfer_completed_after_construction_without_manual_refresh()
    {
        var store = new HistoryStore(_path);
        var host = new Slipstream.App.Tests.Fakes.FakePeerHost();
        var queue = new TransferQueue(host, maxConcurrent: 1);

        // Mirrors ShellWindow's wiring: the queue's terminal updates are what persist new
        // entries into the store. HistoryViewModel itself must pick up the change live via its
        // own ItemUpdated subscription — this test never calls Refresh() or reconstructs the vm.
        queue.ItemUpdated += item =>
        {
            if (item.Status is TransferStatus.Complete or TransferStatus.Failed)
            {
                store.Add(new HistoryEntry(
                    item.Path, item.LocalPath ?? item.Path, item.TotalBytes, item.Status, DateTimeOffset.UtcNow));
            }
        };

        var vm = new HistoryViewModel(store, queue);
        Assert.Empty(vm.Items);

        queue.Enqueue("/DCIM/100APPLE/IMG_0003.HEIC", 20);
        await queue.WaitForIdleAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(5));

        // No dispatcher was supplied, so HistoryViewModel's RunOnUiThread falls back to running
        // inline — the update should already be applied synchronously once the queue is idle.
        var row = Assert.Single(vm.Items);
        Assert.Equal("/DCIM/100APPLE/IMG_0003.HEIC", row.RemotePath);
    }
}
