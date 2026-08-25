using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Slipstream.App.Shell;

/// <summary>
/// Picks the placeholder <see cref="DataTemplate"/> for the currently selected shell
/// destination. Each template is a simple stand-in TextBlock; later tasks add the real
/// DevicePage/BrowsePage/TransfersPage/HistoryPage/SettingsPage content and this selector's
/// templates are the only thing that needs to change.
/// </summary>
public sealed class DestinationTemplateSelector : DataTemplateSelector
{
    public DataTemplate? DeviceTemplate { get; set; }
    public DataTemplate? BrowsePhoneTemplate { get; set; }
    public DataTemplate? TransfersTemplate { get; set; }
    public DataTemplate? HistoryTemplate { get; set; }
    public DataTemplate? SettingsTemplate { get; set; }

    protected override DataTemplate? SelectTemplateCore(object item)
        => item is ShellDestination destination
            ? destination.Label switch
            {
                "Device" => DeviceTemplate,
                "Browse phone" => BrowsePhoneTemplate,
                "Transfers" => TransfersTemplate,
                "History" => HistoryTemplate,
                "Settings" => SettingsTemplate,
                _ => null,
            }
            : null;

    protected override DataTemplate? SelectTemplateCore(object item, DependencyObject container)
        => SelectTemplateCore(item);
}
