using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Slipstream.App.Services;

namespace Slipstream_App.Shell;

/// <summary>
/// The application shell: 260px sidebar navigation, top bar with page title and connection
/// status, and a content area that swaps per destination. The five destination pages
/// (Device, Browse phone, Transfers, History, Settings) are stand-in placeholders here —
/// later tasks replace each <see cref="DataTemplate"/> with the real page content; nothing
/// about the shell's navigation or status wiring needs to change when that happens.
/// </summary>
public sealed partial class ShellWindow : Window
{
    public Slipstream.App.Shell.ShellViewModel ViewModel { get; }

    public string PageSubtitle => ViewModel.Selected.Label switch
    {
        "Device" => "This machine, at a glance.",
        "Browse phone" => "Explore files on the connected device.",
        "Transfers" => "Active and recent transfers.",
        "History" => "Everything sent and received.",
        "Settings" => "Preferences for this device.",
        _ => string.Empty,
    };

    public ShellWindow(IPeerHost peerHost)
    {
        ViewModel = new Slipstream.App.Shell.ShellViewModel(peerHost);

        InitializeComponent();

        // One TransferQueue for the whole session, shared between the Transfers page (which
        // drives it) and the Device page (which reads its Active/ItemUpdated to show a live
        // hero rate) — see DevicePage/DeviceViewModel's TransferQueue remarks.
        var transferQueue = new TransferQueue(peerHost, maxConcurrent: 2);

        // One HistoryStore for the whole session (Task 13), backed by the JSON file under
        // %LOCALAPPDATA%\Slipstream\history.json (HistoryStore.DefaultPath). Every transfer
        // the queue finishes — success or failure — is recorded, via ItemUpdated firing once
        // more with a terminal Status when RunAsync's finally block runs. ItemUpdated fires
        // from whatever background thread ran the transfer (see TransferQueue's remarks);
        // HistoryStore.Add is thread-safe on its own (internally locked), so no UI-thread
        // marshal is needed here — only HistoryViewModel's UI-bound state has to hop threads.
        var historyStore = new HistoryStore();
        var settingsStore = new SettingsStore();
        transferQueue.ItemUpdated += item =>
        {
            if (item.Status is TransferStatus.Complete or TransferStatus.Failed)
            {
                historyStore.Add(new Slipstream.App.Services.HistoryEntry(
                    item.Path, item.LocalPath ?? item.Path, item.TotalBytes, item.Status, DateTimeOffset.UtcNow));
            }
        };

        // See the DeviceTemplate's ContentPresenter comment: a DataTemplate can't take
        // constructor arguments, so the one DevicePage instance is built here (where the
        // injected peerHost is in scope) and hosted by name from the resource dictionary.
        RootGrid.Resources["DevicePageContent"] = new Pages.DevicePage(peerHost, transferQueue, historyStore);
        RootGrid.Resources["BrowsePageContent"] = new Pages.BrowsePage(peerHost, transferQueue);
        RootGrid.Resources["TransfersPageContent"] = new Pages.TransfersPage(transferQueue);
        RootGrid.Resources["HistoryPageContent"] = new Pages.HistoryPage(historyStore, transferQueue);
        RootGrid.Resources["SettingsPageContent"] = new Pages.SettingsPage(settingsStore, peerHost);

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);

        AppWindow.SetIcon("Assets/AppIcon.ico");

        AppWindow.Closing += OnAppWindowClosing;
    }

    /// <summary>Set by the tray icon's "Quit" handler right before it closes this window for
    /// real — the one path that bypasses hide-to-tray (Task 15, plan §14).</summary>
    public bool AllowExit { get; set; }

    /// <summary>Restores and focuses this window — the tray icon's "Show" action and a
    /// second app launch both funnel here (Task 15's single-instance handling).</summary>
    public void ShowAndActivate()
    {
        AppWindow.Show();
        Activate();

        // AppWindow.Show() alone can leave the window behind other apps if it was minimized
        // to the tray rather than merely occluded; bring it fully forward.
        var presenter = AppWindow.Presenter as Microsoft.UI.Windowing.OverlappedPresenter;
        presenter?.Restore();
    }

    private void OnAppWindowClosing(Microsoft.UI.Windowing.AppWindow sender, Microsoft.UI.Windowing.AppWindowClosingEventArgs args)
    {
        if (AllowExit) return;

        // Closing the main window hides it to the tray instead of exiting the process (plan
        // §14) — only the tray menu's Quit (which sets AllowExit first) really closes it.
        args.Cancel = true;
        AppWindow.Hide();
    }

    private void NavList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        Bindings.Update();
    }
}
