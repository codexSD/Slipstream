using CommunityToolkit.Mvvm.ComponentModel;
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
public sealed partial class ShellViewModel : ObservableObject
{
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

    public ShellViewModel(IPeerHost peerHost)
    {
        ArgumentNullException.ThrowIfNull(peerHost);
        _selected = Destinations[0];

        peerHost.StateChanged += OnPeerStateChanged;
    }

    private void OnPeerStateChanged(PeerConnectionState state, string? peerName, string? band)
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
    }
}
