using System.Runtime.InteropServices;
using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Dispatching;
using Slipstream.App.Services;

namespace Slipstream.App.Pages;

/// <summary>One row of the discovery-detail panel (spec §5's S1–S4 strategy ladder).</summary>
public sealed record DiscoveryStrategyRow(string Code, string Name, bool IsWinner);

/// <summary>
/// Drives the Device page: a 3-up stat row (link rate, transferred today, peer state), a
/// hero metric for the live rate, and a connection panel naming the winning discovery
/// strategy and how long it took.
/// </summary>
/// <remarks>
/// <see cref="HeroRateText"/> now reflects the live aggregate rate of whatever
/// <see cref="TransferQueue"/> is currently running (Task 12), when one is supplied — summed
/// across every <see cref="TransferQueue.Active"/> item and refreshed on each
/// <see cref="TransferQueue.ItemUpdated"/>, which fires from a background thread inside
/// <see cref="TransferQueue.RunAsync"/> and is marshaled back onto the UI thread via
/// <see cref="DispatcherQueue"/> before <see cref="HeroRateText"/> is touched (same mechanism
/// <c>TransfersViewModel</c> uses for its throttled grid updates). When no queue is supplied
/// (or nothing is running) it falls back to "—". <see cref="LinkRateText"/> and <see cref="TransferredTodayText"/>
/// still show the resting placeholder "—": no per-day-transferred-bytes aggregate exists yet
/// (no history/stats store), and link rate is a different, still-unavailable measurement (the
/// negotiated/PHY rate, not a transfer's observed throughput). Those remain a documented gap.
/// </remarks>
public sealed partial class DeviceViewModel : ObservableObject, IDisposable
{
    private static readonly IReadOnlyList<(string Code, string Name)> Strategies =
    [
        ("S1", "Cached endpoint"),
        ("S2", "Gateway probe"),
        ("S3", "Multicast"),
        ("S4", "Subnet sweep"),
    ];

    private static readonly IReadOnlyDictionary<string, string> StrategyCodeByCoreName =
        new Dictionary<string, string>
        {
            ["cached-endpoint"] = "S1",
            ["gateway-probe"] = "S2",
            ["multicast"] = "S3",
            ["subnet-sweep"] = "S4",
        };

    private readonly IPeerHost _peerHost;
    private readonly TransferQueue? _transferQueue;
    private readonly HistoryStore? _historyStore;
    private readonly DispatcherQueue? _dispatcher;

    private string _linkRateText = "—";
    private string _transferredTodayText = "—";
    private string _peerStateText = "Not connected";
    private string _heroRateText = "—";
    private IReadOnlyList<DiscoveryStrategyRow> _discoveryStrategies;
    private string _discoverySummaryText = "Not yet connected.";

    /// <summary>Pre-formatted for tabular display (e.g. "52.4 MB/s"). Resting "—" — see remarks.</summary>
    public string LinkRateText
    {
        get => _linkRateText;
        set => SetProperty(ref _linkRateText, value);
    }

    /// <summary>Pre-formatted for tabular display (e.g. "1.2 GB"). Resting "—" — see remarks.</summary>
    public string TransferredTodayText
    {
        get => _transferredTodayText;
        set => SetProperty(ref _transferredTodayText, value);
    }

    /// <summary>e.g. "Connected", "Searching…", "Not connected".</summary>
    public string PeerStateText
    {
        get => _peerStateText;
        set => SetProperty(ref _peerStateText, value);
    }

    /// <summary>The one-per-screen hero number. Resting "—" — see remarks.</summary>
    public string HeroRateText
    {
        get => _heroRateText;
        set => SetProperty(ref _heroRateText, value);
    }

    /// <summary>All four discovery strategies (spec §5), with the winner (if any) flagged.</summary>
    public IReadOnlyList<DiscoveryStrategyRow> DiscoveryStrategies
    {
        get => _discoveryStrategies;
        set => SetProperty(ref _discoveryStrategies, value);
    }

    /// <summary>e.g. "Connected via gateway probe in 1.2s", or "Not yet connected."</summary>
    public string DiscoverySummaryText
    {
        get => _discoverySummaryText;
        set => SetProperty(ref _discoverySummaryText, value);
    }

    public DeviceViewModel(IPeerHost peerHost, TransferQueue? transferQueue = null, DispatcherQueue? dispatcher = null, HistoryStore? historyStore = null)
    {
        ArgumentNullException.ThrowIfNull(peerHost);
        _peerHost = peerHost;
        _transferQueue = transferQueue;
        _historyStore = historyStore;
        _dispatcher = dispatcher ?? TryGetCurrentDispatcher();

        _discoveryStrategies = BuildStrategyRows();
        Refresh(peerHost.State, peerHost.PeerName);

        peerHost.StateChanged += OnPeerStateChanged;
        if (_transferQueue is not null)
            _transferQueue.ItemUpdated += OnTransferUpdated;
    }

    /// <summary>Unsubscribes from the long-lived <see cref="IPeerHost.StateChanged"/> and
    /// <see cref="TransferQueue.ItemUpdated"/> events this view model subscribed to at
    /// construction. Harmless today (the shell builds exactly one long-lived instance per
    /// session, matching IPeerHost's/TransferQueue's own lifetime), but correct to have in
    /// case a page ever stops being a singleton.</summary>
    public void Dispose()
    {
        _peerHost.StateChanged -= OnPeerStateChanged;
        if (_transferQueue is not null)
            _transferQueue.ItemUpdated -= OnTransferUpdated;
    }

    // StateChanged now fires from PeerHost's background reconnect loop (Task 16's
    // network-change handling) far more often than it used to, so — like
    // OnTransferUpdated below — this must be marshaled onto the UI thread before Refresh
    // touches any observable property.
    private void OnPeerStateChanged(PeerConnectionState state, string? peerName, string? band)
        => RunOnUiThread(() => Refresh(state, peerName));

    // TransferQueue.ItemUpdated fires from a background thread inside TransferQueue.RunAsync
    // (see its class remarks), so the resulting HeroRateText mutation must be marshaled onto
    // the UI thread — mirrors TransfersViewModel's RunOnUiThread helper.
    private void OnTransferUpdated(TransferItem item) => RunOnUiThread(() =>
    {
        RefreshHeroRate();

        // A transfer just finished (successfully or not) — today's completed-bytes total
        // may have changed. HistoryStore.Add runs synchronously in ShellWindow's own
        // ItemUpdated subscriber, so by the time this handler observes a terminal status
        // the new entry is already persisted and ready to be summed.
        if (item.Status is TransferStatus.Complete or TransferStatus.Failed)
            RefreshTransferredToday();
    });

    /// <summary>Sums <see cref="BytesPerSecond"/> across every currently-running transfer.
    /// Callers must already be on the UI thread — see <see cref="OnTransferUpdated"/>.</summary>
    private void RefreshHeroRate()
    {
        if (_transferQueue is null) return;

        var totalRate = _transferQueue.Active.Sum(t => t.BytesPerSecond);
        HeroRateText = totalRate > 0 ? TransferItem.FormatRate(totalRate) : "—";
    }

    private void RunOnUiThread(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess)
            action();
        else
            _dispatcher.TryEnqueue(() => action());
    }

    /// <summary>Wraps <see cref="DispatcherQueue.GetForCurrentThread"/>, which throws a
    /// COMException off a real UI thread (e.g. under headless `dotnet test`, per Tasks
    /// 5/7/8's findings) rather than returning null the way its name implies. Falling back to
    /// null here is safe: <see cref="RunOnUiThread"/> already treats a null dispatcher as "run
    /// inline", the same fallback WinUI code hits when constructed off-thread.</summary>
    private static DispatcherQueue? TryGetCurrentDispatcher()
    {
        try
        {
            return DispatcherQueue.GetForCurrentThread();
        }
        catch (COMException)
        {
            return null;
        }
    }

    private void Refresh(PeerConnectionState state, string? peerName)
    {
        PeerStateText = state switch
        {
            PeerConnectionState.Connected => "Connected",
            PeerConnectionState.Searching => "Searching…",
            PeerConnectionState.Degraded => "Degraded",
            PeerConnectionState.Lost => "Connection lost",
            PeerConnectionState.Idle => "Not connected",
            _ => "Not connected",
        };

        DiscoveryStrategies = BuildStrategyRows();
        DiscoverySummaryText = BuildSummary(state);

        // LinkRateText has no live data source (no Wi-Fi metrics anywhere in Core) — an
        // honest resting placeholder, not a fabricated value. TransferredTodayText, unlike
        // LinkRateText, IS computable now that HistoryStore exists — see RefreshTransferredToday.
        LinkRateText = "—";
        RefreshTransferredToday();
        RefreshHeroRate();
    }

    /// <summary>Sums the size of every entry in <see cref="HistoryStore"/> whose
    /// <see cref="HistoryEntry.CompletedAtUtc"/> falls on today's local date, formatted the
    /// same way <c>HistoryViewModel.SizeText</c> formats a single entry's size. Resting "—"
    /// when no HistoryStore was supplied (matches every other unavailable-metric case on this
    /// page) or nothing has completed today.</summary>
    private void RefreshTransferredToday()
    {
        if (_historyStore is null)
        {
            TransferredTodayText = "—";
            return;
        }

        var today = DateTimeOffset.Now.Date;
        var totalBytes = _historyStore.GetAll()
            .Where(entry => entry.Status == TransferStatus.Complete && entry.CompletedAtUtc.ToLocalTime().Date == today)
            .Sum(entry => entry.TotalBytes);

        TransferredTodayText = totalBytes > 0 ? FormatSize(totalBytes) : "—";
    }

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

    private IReadOnlyList<DiscoveryStrategyRow> BuildStrategyRows()
    {
        var winnerCode = _peerHost.DiscoveryStrategy is { } name && StrategyCodeByCoreName.TryGetValue(name, out var code)
            ? code
            : null;

        return Strategies
            .Select(s => new DiscoveryStrategyRow(s.Code, s.Name, s.Code == winnerCode))
            .ToList();
    }

    private string BuildSummary(PeerConnectionState state)
    {
        if (_peerHost.DiscoveryStrategy is null || _peerHost.DiscoveryElapsed is not { } elapsed)
            return "Not yet connected.";

        var strategyName = Strategies.FirstOrDefault(s =>
            StrategyCodeByCoreName.TryGetValue(_peerHost.DiscoveryStrategy, out var code) && s.Code == code).Name
            ?? _peerHost.DiscoveryStrategy;

        var verb = state == PeerConnectionState.Connected ? "Connected" : "Found";
        return $"{verb} via {strategyName.ToLowerInvariant()} in {elapsed.TotalSeconds:0.0}s";
    }
}
