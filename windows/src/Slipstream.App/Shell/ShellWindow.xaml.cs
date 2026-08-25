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
        RootGrid.Resources["DevicePageContent"] = new Pages.DevicePage(peerHost, transferQueue);
        RootGrid.Resources["BrowsePageContent"] = new Pages.BrowsePage(peerHost);
        RootGrid.Resources["TransfersPageContent"] = new Pages.TransfersPage(transferQueue);
        RootGrid.Resources["HistoryPageContent"] = new Pages.HistoryPage(historyStore, transferQueue);
        RootGrid.Resources["SettingsPageContent"] = new Pages.SettingsPage(settingsStore, peerHost);

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);

        AppWindow.SetIcon("Assets/AppIcon.ico");
    }

    private void NavList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        Bindings.Update();
    }
}
