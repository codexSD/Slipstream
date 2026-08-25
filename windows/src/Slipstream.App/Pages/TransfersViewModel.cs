using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Dispatching;
using Slipstream.App.Services;

namespace Slipstream.App.Pages;

/// <summary>
/// Drives the Transfers page: a MeridianDataGrid of every transfer this session has queued,
/// with a live progress/rate/ETA/status per row. Progress reports arrive from
/// <see cref="TransferQueue"/> off the UI thread and can be frequent (Core throttles to
/// ~4/s per transfer, times however many run concurrently), so this view model coalesces
/// them behind a UI-thread timer rather than dispatching one update per report — the grid
/// refreshes at most 4 times/sec regardless of how many transfers are in flight.
/// </summary>
public sealed partial class TransfersViewModel : ObservableObject
{
    private const int MaxUpdatesPerSecond = 4;

    private readonly TransferQueue _queue;
    private readonly DispatcherQueue? _dispatcher;
    private readonly DispatcherQueueTimer? _throttle;
    private readonly HashSet<TransferItem> _dirtyItems = [];
    private readonly object _dirtySync = new();

    /// <summary>Every transfer queued this session, in enqueue order. Bound directly to
    /// MeridianDataGrid's ItemsSource.</summary>
    public ObservableCollection<TransferItem> Items { get; } = [];

    public TransfersViewModel(TransferQueue queue, DispatcherQueue? dispatcher = null)
    {
        ArgumentNullException.ThrowIfNull(queue);
        _queue = queue;
        _dispatcher = dispatcher ?? DispatcherQueue.GetForCurrentThread();

        _queue.ItemEnqueued += OnItemEnqueued;
        _queue.ItemUpdated += OnItemUpdated;

        if (_dispatcher is not null)
        {
            _throttle = _dispatcher.CreateTimer();
            _throttle.Interval = TimeSpan.FromMilliseconds(1000d / MaxUpdatesPerSecond);
            _throttle.Tick += (_, _) => FlushIfDirty();
            _throttle.Start();
        }
    }

    /// <summary>Enqueues a new transfer by remote path (e.g. from a Browse-page selection).</summary>
    [RelayCommand]
    public void Enqueue(string remotePath) => _queue.Enqueue(remotePath);

    private void OnItemEnqueued(TransferItem item)
    {
        // A brand-new row is added immediately (not throttled) so queuing feels responsive;
        // only the high-frequency progress updates on existing rows are coalesced.
        RunOnUiThread(() =>
        {
            if (!Items.Contains(item))
                Items.Add(item);
        });
    }

    private void OnItemUpdated(TransferItem item)
    {
        lock (_dirtySync) _dirtyItems.Add(item);
    }

    private void FlushIfDirty()
    {
        List<TransferItem> dirty;
        lock (_dirtySync)
        {
            if (_dirtyItems.Count == 0) return;
            dirty = [.. _dirtyItems];
            _dirtyItems.Clear();
        }

        RunOnUiThread(() =>
        {
            // TransferItem's own properties are ObservableObject-backed (per-property
            // PropertyChanged), but MeridianDataGrid resolves cell text once per container via
            // reflection rather than binding (see its class remarks). Re-assigning each dirty
            // row through the collection indexer raises a Replace notification for that index,
            // which is what makes MeridianDataGrid regenerate the container's cell text.
            foreach (var item in dirty)
            {
                var index = Items.IndexOf(item);
                if (index >= 0)
                    Items[index] = item;
            }
        });
    }

    private void RunOnUiThread(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess)
            action();
        else
            _dispatcher.TryEnqueue(() => action());
    }
}
