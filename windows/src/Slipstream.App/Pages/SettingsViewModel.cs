using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Dispatching;
using Slipstream.App.Services;

namespace Slipstream.App.Pages;

/// <summary>
/// Drives the Settings page: stream count (clamped to [1,8]), download folder (falls back to
/// the default Downloads folder if the chosen path no longer exists on disk), theme choice,
/// and an autostart preference — all backed by <see cref="SettingsStore"/> (Task 14's
/// settings.json, mirroring Task 13's HistoryStore convention). Also exposes "Pair a device"
/// and the band the shell currently reports, per spec §16's PC-hosts-the-hotspot explainer.
/// </summary>
/// <remarks>
/// <para>
/// <b>Pair a device</b> is wired through an injected <c>pairDeviceLauncher</c> delegate rather
/// than constructing a <see cref="PairingDialog"/> directly, so <see cref="PairDeviceCommand"/>
/// is testable off the UI thread (a real <c>ContentDialog</c> needs a live XamlRoot). The real
/// launcher, supplied by <c>SettingsPage</c>, is <c>new PairingDialog(peerHost).ShowAndPairAsync</c>.
/// </para>
/// <para>
/// <b>Autostart</b> (Task 15's actual Task-Scheduler-backed <c>AutostartService</c> is not
/// implemented here — that is explicitly Task 15's responsibility). This task stores only the
/// user's preference (a bool) in <see cref="SettingsStore"/> and exposes it as
/// <see cref="AutostartEnabled"/>; Task 15 wires that preference to the real registration by
/// reading/writing through this same property (or the store directly) rather than introducing
/// a second source of truth. No <c>IAutostartService</c> seam is declared — a bool preference
/// is the whole of this task's scope, and a one-member interface added now with no consumer
/// would just be speculative surface Task 15 may not want anyway.
/// </para>
/// </remarks>
public sealed partial class SettingsViewModel : ObservableObject
{
    private readonly SettingsStore _store;
    private readonly IPeerHost _peerHost;
    private readonly Func<CancellationToken, Task>? _pairDeviceLauncher;
    private readonly DispatcherQueue? _dispatcher;

    private SettingsData _data;

    private int _streamCount;
    private string _downloadDirectory;
    private AppTheme _theme;
    private bool _autostartEnabled;
    private string _bandText;

    public SettingsViewModel(
        SettingsStore store,
        IPeerHost peerHost,
        Func<CancellationToken, Task>? pairDeviceLauncher = null,
        DispatcherQueue? dispatcher = null)
    {
        ArgumentNullException.ThrowIfNull(store);
        ArgumentNullException.ThrowIfNull(peerHost);

        _store = store;
        _peerHost = peerHost;
        _pairDeviceLauncher = pairDeviceLauncher;
        _dispatcher = dispatcher ?? TryGetCurrentDispatcher();

        _data = _store.Load();
        _streamCount = _data.StreamCount;
        _downloadDirectory = _data.DownloadDirectory;
        _theme = _data.Theme;
        _autostartEnabled = _data.AutostartEnabled;
        _bandText = FormatBand(peerHost.Band);

        PairDeviceCommand = new AsyncRelayCommand(PairDeviceAsync);

        peerHost.StateChanged += OnPeerStateChanged;
    }

    /// <summary>Number of parallel streams a pull uses. Clamped to [1,8] — spec's bounded
    /// range for SlipstreamPeer.StreamCount (Slipstream.Core.SlipstreamPeer).</summary>
    public int StreamCount
    {
        get => _streamCount;
        set
        {
            var clamped = Math.Clamp(value, 1, 8);
            if (SetProperty(ref _streamCount, clamped))
                Persist(_data with { StreamCount = clamped });
        }
    }

    /// <summary>Where pulled files are saved. Falls back to
    /// <see cref="SettingsStore.DefaultDownloadDirectory"/> if the assigned path does not
    /// exist on disk — a folder that was moved or deleted since it was chosen is not a valid
    /// destination to keep silently pointing at.</summary>
    public string DownloadDirectory
    {
        get => _downloadDirectory;
        set
        {
            var resolved = Directory.Exists(value) ? value : SettingsStore.DefaultDownloadDirectory();
            if (SetProperty(ref _downloadDirectory, resolved))
                Persist(_data with { DownloadDirectory = resolved });
        }
    }

    public AppTheme Theme
    {
        get => _theme;
        set
        {
            if (SetProperty(ref _theme, value))
                Persist(_data with { Theme = value });
        }
    }

    /// <summary>The user's stored preference for launching at sign-in. This task persists the
    /// preference only — see the class remarks for why the actual Task Scheduler
    /// registration is left to Task 15.</summary>
    public bool AutostartEnabled
    {
        get => _autostartEnabled;
        set
        {
            if (SetProperty(ref _autostartEnabled, value))
                Persist(_data with { AutostartEnabled = value });
        }
    }

    /// <summary>The Wi-Fi band the shell currently reports for the connected peer (e.g. "5
    /// GHz"), or "Unknown" — feeds the "PC hosts the hotspot" explainer per spec §16.</summary>
    public string BandText
    {
        get => _bandText;
        private set => SetProperty(ref _bandText, value);
    }

    public AsyncRelayCommand PairDeviceCommand { get; }

    private Task PairDeviceAsync() => _pairDeviceLauncher?.Invoke(CancellationToken.None) ?? Task.CompletedTask;

    private void OnPeerStateChanged(PeerConnectionState state, string? peerName, string? band) =>
        RunOnUiThread(() => BandText = FormatBand(band));

    private static string FormatBand(string? band) => string.IsNullOrWhiteSpace(band) ? "Unknown" : band;

    private void Persist(SettingsData data)
    {
        _data = data;
        _store.Save(data);
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
