using System.Threading;
using Windows.ApplicationModel;
using Windows.ApplicationModel.Activation;
using Windows.Foundation;
using Windows.Foundation.Collections;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Microsoft.UI.Xaml.Shapes;
using AppShell = Slipstream.App.Shell;
using Slipstream.App.Services;

// To learn more about WinUI, the WinUI project structure,
// and more about our project templates, see: http://aka.ms/winui-project-info.

namespace Slipstream_App;

/// <summary>
/// Provides application-specific behavior to supplement the default Application class.
/// </summary>
public partial class App : Application
{
    // Single-instance enforcement (Task 15, plan: "a second launch surfaces the first window
    // instead of starting a second peer that would fight for port 53321"). A named mutex is
    // the simplest reliable way to detect "am I the first instance"; a named event is the
    // simplest reliable way to ask that first instance to show itself, since the two
    // processes share nothing else. "Global\" makes both visible across sessions, matching
    // how a user might launch a second copy from a different login session.
    private const string SingleInstanceMutexName = "Global\\Slipstream.SingleInstance";
    private const string ShowFirstInstanceEventName = "Global\\Slipstream.ShowFirstInstance";

    private Mutex? _singleInstanceMutex;
    private EventWaitHandle? _showRequestedEvent;
    private Window? _window;
    private Slipstream_App.Shell.ShellWindow? _shellWindow;
    private TrayIcon? _trayIcon;
    private IPeerHost? _peerHost;

    /// <summary>The single top-level window, exposed for pages that need an owner HWND for
    /// WinRT pickers (e.g. SettingsPage's FolderPicker) — unpackaged desktop apps must
    /// initialize such pickers with an explicit window handle.</summary>
    public static Window? MainWindowInstance { get; private set; }

    /// <summary>
    /// Initializes the singleton application object.  This is the first line of authored code
    /// executed, and as such is the logical equivalent of main() or WinMain().
    /// </summary>
    public App()
    {
        InitializeComponent();
    }

    /// <summary>
    /// Invoked when the application is launched.
    /// </summary>
    /// <param name="args">Details about the launch request and process.</param>
    protected override void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args)
    {
        _singleInstanceMutex = new Mutex(initiallyOwned: true, SingleInstanceMutexName, out var createdNew);

        if (!createdNew)
        {
            // Another instance already owns the mutex: ask it to show itself, then exit
            // immediately without starting a second peer (which would fight the first one
            // for port 53321).
            try
            {
                using var showEvent = EventWaitHandle.OpenExisting(ShowFirstInstanceEventName);
                showEvent.Set();
            }
            catch (WaitHandleCannotBeOpenedException)
            {
                // The first instance is mid-startup and has not created the event yet; there
                // is nothing more this instance can usefully do either way.
            }

            Environment.Exit(0);
            return;
        }

        // Own the mutex: this is the first (and only) instance. Listen for later launches
        // asking to be shown, for as long as this instance lives.
        _showRequestedEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowFirstInstanceEventName);
        WatchForShowRequestsAsync();

        // TODO(Task 8): replace NoOpPeerHost with the real PeerHost implementation.
        _peerHost = new AppShell.NoOpPeerHost();

        _shellWindow = new Slipstream_App.Shell.ShellWindow(_peerHost);
        _window = _shellWindow;
        MainWindowInstance = _window;
        _window.Activate();

        _trayIcon = new TrayIcon(
            System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"),
            tooltip: "Slipstream");
        _trayIcon.ShowRequested += () => _window.DispatcherQueue.TryEnqueue(() => _shellWindow.ShowAndActivate());
        _trayIcon.PauseDiscoveryToggled += paused => _window.DispatcherQueue.TryEnqueue(() =>
        {
            if (paused) _peerHost.PauseDiscovery();
            else _peerHost.ResumeDiscovery();
        });
        _trayIcon.QuitRequested += () => _window.DispatcherQueue.TryEnqueue(Quit);
    }

    /// <summary>Actually terminates the app — the tray menu's Quit is the only path that
    /// reaches this; the window's own close button hides to tray instead (see
    /// <c>ShellWindow.OnAppWindowClosing</c>).</summary>
    private void Quit()
    {
        if (_shellWindow is not null) _shellWindow.AllowExit = true;
        _trayIcon?.Dispose();
        _singleInstanceMutex?.ReleaseMutex();
        _window?.Close();
        Exit();
    }

    private async void WatchForShowRequestsAsync()
    {
        if (_showRequestedEvent is null) return;

        while (true)
        {
            await Task.Run(() => _showRequestedEvent.WaitOne());
            _window?.DispatcherQueue.TryEnqueue(() => _shellWindow?.ShowAndActivate());
        }
    }
}
