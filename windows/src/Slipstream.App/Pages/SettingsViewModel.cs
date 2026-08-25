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
    private readonly AutostartService? _autostartService;

    private SettingsData _data;

    private int _streamCount;
    private string _downloadDirectory;
    private AppTheme _theme;
    private bool _autostartEnabled;
    private string _bandText;
    private string? _autostartError;

    public SettingsViewModel(
        SettingsStore store,
        IPeerHost peerHost,
        Func<CancellationToken, Task>? pairDeviceLauncher = null,
        DispatcherQueue? dispatcher = null,
        AutostartService? autostartService = null)
    {
        ArgumentNullException.ThrowIfNull(store);
        ArgumentNullException.ThrowIfNull(peerHost);

        _store = store;
        _peerHost = peerHost;
        _pairDeviceLauncher = pairDeviceLauncher;
        _dispatcher = dispatcher ?? TryGetCurrentDispatcher();
        _autostartService = autostartService;

        _data = _store.Load();
        _streamCount = _data.StreamCount;
        _downloadDirectory = _data.DownloadDirectory;
        _theme = _data.Theme;

        // The real Task Scheduler state (queried live) is the source of truth for what
        // Settings shows on load, not the persisted preference alone — a task deleted by
        // hand, or one that failed to register on a previous run, should not leave the
        // toggle silently lying. When no AutostartService is supplied (e.g. plain
        // preference-only tests), fall back to the stored preference exactly as Task 14 did.
        _autostartEnabled = _autostartService?.IsEnabled ?? _data.AutostartEnabled;
        if (_autostartService is not null && _autostartEnabled != _data.AutostartEnabled)
            Persist(_data with { AutostartEnabled = _autostartEnabled });

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

    /// <summary>The user's autostart preference. When an <see cref="AutostartService"/> was
    /// supplied, setting this actually creates/removes the real Task Scheduler logon task —
    /// the persisted bool and the OS state are kept in sync, not two disconnected sources of
    /// truth. If the real registration fails (e.g. a permissions problem), the toggle reverts
    /// and <see cref="AutostartError"/> reports why, rather than silently pretending it
    /// worked.</summary>
    public bool AutostartEnabled
    {
        get => _autostartEnabled;
        set
        {
            if (_autostartEnabled == value) return;

            if (_autostartService is not null)
            {
                try
                {
                    if (value) _autostartService.Enable();
                    else _autostartService.Disable();
                    AutostartError = null;
                }
                catch (InvalidOperationException ex)
                {
                    // Direct, no apology, names the next step (per spec §15's error voice):
                    // report what happened and leave the toggle where it actually is, rather
                    // than showing a state that is not real.
                    AutostartError = $"Couldn't {(value ? "enable" : "disable")} start at sign-in. {ex.Message}";
                    OnPropertyChanged(nameof(AutostartEnabled));
                    return;
                }
            }

            if (SetProperty(ref _autostartEnabled, value))
                Persist(_data with { AutostartEnabled = value });
        }
    }

    /// <summary>Set when the last <see cref="AutostartEnabled"/> change failed to apply to
    /// the real Task Scheduler registration; null otherwise. SettingsPage surfaces this as an
    /// inline error rather than letting the toggle silently fail.</summary>
    public string? AutostartError
    {
        get => _autostartError;
        private set
        {
            if (SetProperty(ref _autostartError, value))
                OnPropertyChanged(nameof(HasAutostartError));
        }
    }

    /// <summary>Convenience for the XAML binding — WinUI's <c>BoolToVisibilityConverter</c>
    /// needs a bool, and a null-ness check reads more directly here than a converter chain.</summary>
    public bool HasAutostartError => _autostartError is not null;

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
