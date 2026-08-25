using CommunityToolkit.Mvvm.ComponentModel;
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
/// <see cref="LinkRateText"/>, <see cref="TransferredTodayText"/>, and
/// <see cref="HeroRateText"/> show the resting placeholder "—" rather than a fabricated
/// number: <see cref="IPeerHost"/> does not expose a live per-transfer byte rate today (that
/// arrives with Task 12's TransferQueue, which observes <c>TransferProgress</c> from an
/// in-flight pull) or a per-day-transferred-bytes aggregate (no history/stats store exists
/// yet). This is a deliberate, documented gap for the controller to sanity-check — wiring a
/// real rate in here once TransferQueue exists is a follow-up, not a workaround.
/// </remarks>
public sealed partial class DeviceViewModel : ObservableObject
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

    public DeviceViewModel(IPeerHost peerHost)
    {
        ArgumentNullException.ThrowIfNull(peerHost);
        _peerHost = peerHost;

        _discoveryStrategies = BuildStrategyRows();
        Refresh(peerHost.State, peerHost.PeerName);

        peerHost.StateChanged += OnPeerStateChanged;
    }

    private void OnPeerStateChanged(PeerConnectionState state, string? peerName, string? band)
        => Refresh(state, peerName);

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

        // No live-rate source is available yet (see remarks) — these stay at their
        // resting placeholder regardless of connection state.
        LinkRateText = "—";
        TransferredTodayText = "—";
        HeroRateText = "—";
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
