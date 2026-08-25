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

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);

        AppWindow.SetIcon("Assets/AppIcon.ico");
    }

    private void NavList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        Bindings.Update();
    }
}
