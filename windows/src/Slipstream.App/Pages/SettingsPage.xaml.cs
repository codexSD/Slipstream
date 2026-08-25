using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Slipstream.App.Services;

namespace Slipstream_App.Pages;

/// <summary>
/// The Settings page (Task 14): stream count, download folder, theme, "Pair a device", and
/// the autostart preference, each in its own MeridianCard with a 20px gap between cards.
/// </summary>
public sealed partial class SettingsPage : Page
{
    private readonly IPeerHost _peerHost;

    public Slipstream.App.Pages.SettingsViewModel ViewModel { get; }

    public SettingsPage(SettingsStore store, IPeerHost peerHost)
    {
        _peerHost = peerHost;
        ViewModel = new Slipstream.App.Pages.SettingsViewModel(
            store, peerHost, pairDeviceLauncher: LaunchPairingDialogAsync);

        InitializeComponent();

        SyncThemeRadios();
    }

    private void SyncThemeRadios()
    {
        var radio = ViewModel.Theme switch
        {
            AppTheme.Light => ThemeLightRadio,
            AppTheme.Dark => ThemeDarkRadio,
            _ => ThemeSystemRadio,
        };
        radio.IsChecked = true;
    }

    private void ThemeRadio_Checked(object sender, RoutedEventArgs e)
    {
        if (sender is not RadioButton { Tag: string tag }) return;

        ViewModel.Theme = tag switch
        {
            "Light" => AppTheme.Light,
            "Dark" => AppTheme.Dark,
            _ => AppTheme.System,
        };

        // Applies the choice live to this page's root, mirroring how an unpackaged WinUI 3
        // app switches theme at runtime (FrameworkElement.RequestedTheme). Best-effort: the
        // XamlRoot's Content is only available once the page is in the visual tree, which it
        // always is by the time a user can click a radio button here.
        if (XamlRoot?.Content is FrameworkElement root)
        {
            root.RequestedTheme = ViewModel.Theme switch
            {
                AppTheme.Light => ElementTheme.Light,
                AppTheme.Dark => ElementTheme.Dark,
                _ => ElementTheme.Default,
            };
        }
    }

    private async void ChooseFolderButton_Click(object sender, RoutedEventArgs e)
    {
        var picker = new Windows.Storage.Pickers.FolderPicker();
        picker.FileTypeFilter.Add("*");

        // FolderPicker requires an owner HWND on desktop (unpackaged) apps. The page's own
        // XamlRoot lives in the same top-level window this page is hosted in, so its HWND is
        // resolved through that window rather than threading one in from ShellWindow.
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindowInstance);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);

        var folder = await picker.PickSingleFolderAsync();
        if (folder is not null)
            ViewModel.DownloadDirectory = folder.Path;
    }

    private async void PairDeviceButton_Click(object sender, RoutedEventArgs e) =>
        await ViewModel.PairDeviceCommand.ExecuteAsync(null);

    private Task LaunchPairingDialogAsync(CancellationToken ct)
    {
        var dialog = new Slipstream_App.Pages.PairingDialog(_peerHost);
        if (XamlRoot is not null)
            dialog.XamlRoot = XamlRoot;

        return dialog.ShowAndPairAsync(ct);
    }
}
