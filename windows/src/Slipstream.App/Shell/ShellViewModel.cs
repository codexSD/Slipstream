using System.Runtime.InteropServices;
using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Dispatching;
using Slipstream.App.Services;
using Slipstream.Meridian.Controls;

namespace Slipstream.App.Shell;

/// <summary>A single entry in the sidebar navigation.</summary>
public sealed record ShellDestination(string Label);

/// <summary>
/// Drives the shell's sidebar navigation and top-bar connection status. Subscribes to
/// <see cref="IPeerHost.StateChanged"/> and maps the peer connection state onto the
/// Meridian status vocabulary (spec §16).
/// </summary>
/// <remarks>
/// <see cref="IPeerHost.StateChanged"/> now fires from PeerHost's background reconnect
/// loop (Task 16's network-change handling) far more often than the one-shot
/// connect/disconnect it used to — every backoff cycle of a teardown/rediscover/resume
/// walks the same event. <see cref="OnPeerStateChanged"/> mutates observable properties, so
/// it must marshal onto the UI thread first, exactly like <c>TransfersViewModel</c>'s and
/// <c>DeviceViewModel</c>'s <c>RunOnUiThread</c> helpers.
/// </remarks>
public sealed partial class ShellViewModel : ObservableObject
{
    private readonly DispatcherQueue? _dispatcher;

    public IReadOnlyList<ShellDestination> Destinations { get; } =
    [
        new("Device"),
        new("Browse phone"),
        new("Transfers"),
        new("History"),
        new("Settings"),
    ];

    private ShellDestination _selected;
    private MeridianStatus _connectionStatus = MeridianStatus.Neutral;
    private string _connectionLabel = "Not connected";

    public ShellDestination Selected
    {
        get => _selected;
        set => SetProperty(ref _selected, value);
    }

    public MeridianStatus ConnectionStatus
    {
        get => _connectionStatus;
        set => SetProperty(ref _connectionStatus, value);
    }

    public string ConnectionLabel
    {
        get => _connectionLabel;
        set => SetProperty(ref _connectionLabel, value);
    }

    public ShellViewModel(IPeerHost peerHost, DispatcherQueue? dispatcher = null)
    {
        ArgumentNullException.ThrowIfNull(peerHost);
        _selected = Destinations[0];
        _dispatcher = dispatcher ?? TryGetCurrentDispatcher();

        peerHost.StateChanged += OnPeerStateChanged;
    }

    // StateChanged can now fire from PeerHost's background reconnect loop (see remarks),
    // so the resulting property mutations must be marshaled onto the UI thread.
    private void OnPeerStateChanged(PeerConnectionState state, string? peerName, string? band)
        => RunOnUiThread(() =>
        {
            (ConnectionStatus, ConnectionLabel) = state switch
            {
                PeerConnectionState.Connected => (MeridianStatus.Positive, peerName ?? "Connected"),
                PeerConnectionState.Searching => (MeridianStatus.Info, "Searching…"),
                PeerConnectionState.Degraded => (MeridianStatus.Warning, $"{band} — slower link"),
                PeerConnectionState.Lost => (MeridianStatus.Critical, "Connection lost"),
                PeerConnectionState.Idle => (MeridianStatus.Neutral, "Not connected"),
                _ => (MeridianStatus.Neutral, "Not connected"),
            };
        });

    private void RunOnUiThread(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess)
            action();
        else
            _dispatcher.TryEnqueue(() => action());
    }

    /// <summary>Wraps <see cref="DispatcherQueue.GetForCurrentThread"/>, which throws a
    /// COMException off a real UI thread (e.g. under headless `dotnet test`) rather than
    /// returning null the way its name implies. Falling back to null here is safe:
    /// <see cref="RunOnUiThread"/> already treats a null dispatcher as "run inline", the
    /// same fallback WinUI code hits when constructed off-thread.</summary>
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
}
