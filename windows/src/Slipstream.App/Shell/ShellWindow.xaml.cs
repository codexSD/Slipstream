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

        // See the DeviceTemplate's ContentPresenter comment: a DataTemplate can't take
        // constructor arguments, so the one DevicePage instance is built here (where the
        // injected peerHost is in scope) and hosted by name from the resource dictionary.
        RootGrid.Resources["DevicePageContent"] = new Pages.DevicePage(peerHost, transferQueue);
        RootGrid.Resources["BrowsePageContent"] = new Pages.BrowsePage(peerHost);
        RootGrid.Resources["TransfersPageContent"] = new Pages.TransfersPage(transferQueue);

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);

        AppWindow.SetIcon("Assets/AppIcon.ico");
    }

    private void NavList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        Bindings.Update();
    }
}
