using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Data;

namespace Slipstream.App.Pages;

/// <summary>Converts a bool into the opposite Visibility — used to hide the data-grid view
/// while the gallery view (a plain bool toggle, not a Visibility) is showing.</summary>
public sealed class InverseBoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language) =>
        value is true ? Visibility.Collapsed : Visibility.Visible;

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        value is not Visibility.Visible;
}
