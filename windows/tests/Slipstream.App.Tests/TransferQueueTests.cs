using Slipstream.App.Services;
using Slipstream.App.Tests.Fakes;
using TransferProgress = Slipstream.Core.Transfer.TransferProgress;

namespace Slipstream.App.Tests;

/// <summary>
/// Task 12: TransferQueue runs enqueued pulls through IPeerHost with bounded concurrency,
/// keeps going after a single transfer fails, and TransferItem formats progress into the
/// tabular size/rate/ETA strings the Transfers page displays.
/// </summary>
public class TransferQueueTests : IAsyncLifetime
{
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(60));

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        await _cts.CancelAsync();
        _cts.Dispose();
    }

    [Fact]
    public async Task Runs_queued_transfers_one_at_a_time()
    {
        var queue = new TransferQueue(new FakePeerHost(), maxConcurrent: 1);
        queue.Enqueue("/a.bin"); queue.Enqueue("/b.bin");

        await queue.WaitForIdleAsync(_cts.Token);

        Assert.Equal(2, queue.Completed.Count);
        Assert.All(queue.Completed, t => Assert.Equal(TransferStatus.Complete, t.Status));
    }

    [Fact]
    public async Task A_failed_transfer_does_not_stop_the_queue()
    {
        var host = new FakePeerHost { FailFor = "/bad.bin" };
        var queue = new TransferQueue(host, maxConcurrent: 1);
        queue.Enqueue("/bad.bin"); queue.Enqueue("/good.bin");

        await queue.WaitForIdleAsync(_cts.Token);

        Assert.Contains(queue.Completed, t => t.Status == TransferStatus.Failed);
        Assert.Contains(queue.Completed, t => t.Status == TransferStatus.Complete);
    }

    [Fact]
    public void Progress_formats_as_tabular_rate_and_eta()
    {
        var item = new TransferItem("/big.bin", 4L * 1024 * 1024 * 1024);
        item.Apply(new TransferProgress(Guid.Empty, 1L << 30, 4L << 30, 50 * 1024 * 1024));

        Assert.Equal("1.0 / 4.0 GB", item.SizeText);
        Assert.Equal("50.0 MB/s", item.RateText);
        Assert.Equal("1m 1s left", item.EtaText);
    }
}
