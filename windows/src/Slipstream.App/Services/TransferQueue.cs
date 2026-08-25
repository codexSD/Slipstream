using Slipstream.Core.Transfer;

namespace Slipstream.App.Services;

/// <summary>
/// Runs enqueued pulls through <see cref="IPeerHost"/> with bounded concurrency. A failed
/// transfer is recorded as <see cref="TransferStatus.Failed"/> and does not stop the rest of
/// the queue from running. Progress reports are forwarded to subscribers via
/// <see cref="ItemUpdated"/> as they arrive from Core (already throttled there); coalescing
/// those to at most a handful of UI refreshes per second is the view model's job (see
/// TransfersViewModel), not this class's.
/// </summary>
public sealed class TransferQueue
{
    private readonly IPeerHost _host;
    private readonly SemaphoreSlim _slots;
    private readonly object _sync = new();
    private readonly List<TransferItem> _completed = [];
    private readonly List<TransferItem> _active = [];
    private int _outstanding;
    private TaskCompletionSource _idle = CreateSignaledTcs();

    public TransferQueue(IPeerHost host, int maxConcurrent)
    {
        ArgumentNullException.ThrowIfNull(host);
        if (maxConcurrent < 1) throw new ArgumentOutOfRangeException(nameof(maxConcurrent));

        _host = host;
        _slots = new SemaphoreSlim(maxConcurrent, maxConcurrent);
    }

    /// <summary>Raised the moment a new item is queued, on whatever thread called Enqueue.</summary>
    public event Action<TransferItem>? ItemEnqueued;

    /// <summary>Raised whenever an item's status or progress changes. Not marshaled to any
    /// particular thread — subscribers that touch the UI must dispatch themselves.</summary>
    public event Action<TransferItem>? ItemUpdated;

    /// <summary>Items that have finished, successfully or not, in completion order.</summary>
    public IReadOnlyList<TransferItem> Completed
    {
        get { lock (_sync) return _completed.ToList(); }
    }

    /// <summary>Items currently mid-transfer. Used to aggregate a live overall rate.</summary>
    public IReadOnlyList<TransferItem> Active
    {
        get { lock (_sync) return _active.ToList(); }
    }

    public TransferItem Enqueue(string remotePath, long totalBytes = 0)
    {
        var item = new TransferItem(remotePath, totalBytes);

        lock (_sync)
        {
            if (_outstanding == 0)
                _idle = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            _outstanding++;
        }

        ItemEnqueued?.Invoke(item);
        _ = RunAsync(item);
        return item;
    }

    private async Task RunAsync(TransferItem item)
    {
        await _slots.WaitAsync().ConfigureAwait(false);
        try
        {
            item.Status = TransferStatus.Running;
            lock (_sync) _active.Add(item);
            ItemUpdated?.Invoke(item);

            var progress = new Progress<TransferProgress>(p =>
            {
                item.Apply(p);
                ItemUpdated?.Invoke(item);
            });

            await _host.PullAsync(item.Path, progress, CancellationToken.None).ConfigureAwait(false);
            item.Status = TransferStatus.Complete;
        }
        catch
        {
            item.Status = TransferStatus.Failed;
        }
        finally
        {
            _slots.Release();

            TaskCompletionSource? toSignal = null;
            lock (_sync)
            {
                _active.Remove(item);
                _completed.Add(item);
                _outstanding--;
                if (_outstanding == 0)
                    toSignal = _idle;
            }

            ItemUpdated?.Invoke(item);
            toSignal?.TrySetResult();
        }
    }

    /// <summary>Completes once every enqueued transfer has finished (succeeded or failed) and
    /// nothing new has been enqueued since. Returns immediately if the queue is already idle.</summary>
    public Task WaitForIdleAsync(CancellationToken ct)
    {
        Task idleTask;
        lock (_sync)
        {
            if (_outstanding == 0) return Task.CompletedTask;
            idleTask = _idle.Task;
        }

        return idleTask.WaitAsync(ct);
    }

    private static TaskCompletionSource CreateSignaledTcs()
    {
        var tcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        tcs.SetResult();
        return tcs;
    }
}
