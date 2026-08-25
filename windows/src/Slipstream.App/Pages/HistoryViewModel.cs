using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Dispatching;
using Slipstream.App.Services;

namespace Slipstream.App.Pages;

/// <summary>
/// One row of the History page: a completed transfer plus the display/derived state
/// MeridianDataGrid needs (CanReveal, pre-formatted size/date). Deliberately not the same
/// object as <see cref="HistoryEntry"/> — that's the plain persisted record; this wraps it
/// with UI-only state (CanReveal depends on whether the file is still on disk *right now*,
/// which is not something the persisted record itself should encode).
/// </summary>
public sealed partial class HistoryRow : ObservableObject
{
    public HistoryRow(HistoryEntry entry)
    {
        Entry = entry;
        _canReveal = File.Exists(entry.LocalPath);
    }

    public HistoryEntry Entry { get; }

    public string Name => Entry.Name;
    public string RemotePath => Entry.RemotePath;
    public string LocalPath => Entry.LocalPath;
    public string StatusText => Entry.Status switch
    {
        TransferStatus.Complete => "Complete",
        TransferStatus.Failed => "Failed",
        TransferStatus.Running => "Running",
        TransferStatus.Queued => "Queued",
        _ => Entry.Status.ToString(),
    };
    public string CompletedText => Entry.CompletedAtUtc.ToLocalTime().ToString("g");
    public string SizeText => FormatSize(Entry.TotalBytes);

    private bool _canReveal;

    /// <summary>Whether "Reveal in folder" should be enabled for this row — false once the
    /// local file has been moved or deleted since the transfer completed.</summary>
    public bool CanReveal
    {
        get => _canReveal;
        set => SetProperty(ref _canReveal, value);
    }

    /// <summary>Re-checks disk state. The row is constructed with a snapshot at load time;
    /// call this if the underlying file may have changed since (e.g. before wiring a click).</summary>
    public void RefreshCanReveal() => CanReveal = File.Exists(Entry.LocalPath);

    private static string FormatSize(long bytes)
    {
        const double Kb = 1024d, Mb = Kb * 1024d, Gb = Mb * 1024d;
        return bytes switch
        {
            >= (long)Gb => $"{bytes / Gb:0.0} GB",
            >= (long)Mb => $"{bytes / Mb:0.0} MB",
            >= (long)Kb => $"{bytes / Kb:0.0} KB",
            _ => $"{bytes} B",
        };
    }
}

/// <summary>
/// Drives the History page: every persisted <see cref="HistoryEntry"/> from
/// <see cref="HistoryStore"/>, newest first, with per-row "reveal in folder" and "run again"
/// actions. Loads once at construction — HistoryStore has no change notifications of its own
/// (unlike TransferQueue), so <see cref="Refresh"/> re-reads the file; the shell calls it
/// whenever History becomes the selected destination, which is the standard "on navigate"
/// refresh point for this kind of page.
/// </summary>
public sealed partial class HistoryViewModel : ObservableObject
{
    private readonly HistoryStore _store;
    private readonly TransferQueue _queue;
    private readonly DispatcherQueue? _dispatcher;

    public ObservableCollection<HistoryRow> Items { get; } = [];

    public HistoryViewModel(HistoryStore store, TransferQueue queue, DispatcherQueue? dispatcher = null)
    {
        ArgumentNullException.ThrowIfNull(store);
        ArgumentNullException.ThrowIfNull(queue);
        _store = store;
        _queue = queue;
        _dispatcher = dispatcher ?? TryGetCurrentDispatcher();

        Refresh();
    }

    /// <summary>Re-reads the store and repopulates <see cref="Items"/>, newest first.</summary>
    public void Refresh()
    {
        var rows = _store.GetAll().Select(e => new HistoryRow(e)).ToList();

        RunOnUiThread(() =>
        {
            Items.Clear();
            foreach (var row in rows)
                Items.Add(row);
        });
    }

    /// <summary>Re-enqueues the given row's remote path via the shared TransferQueue — the
    /// same mechanism the Browse page uses to start a pull.</summary>
    [RelayCommand]
    public void RunAgain(HistoryRow row)
    {
        ArgumentNullException.ThrowIfNull(row);
        _queue.Enqueue(row.RemotePath, row.Entry.TotalBytes);
    }

    private void RunOnUiThread(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess)
            action();
        else
            _dispatcher.TryEnqueue(() => action());
    }

    private static DispatcherQueue? TryGetCurrentDispatcher()
    {
        try
        {
            return DispatcherQueue.GetForCurrentThread();
        }
        catch (System.Runtime.InteropServices.COMException)
        {
            return null;
        }
    }
}
