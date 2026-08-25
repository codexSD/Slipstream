using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Data;

namespace Slipstream.Meridian.Controls;

/// <summary>Converts a bool into Visibility, used by default control templates to collapse
/// optional chrome (e.g. MeridianSectionHeader's trailing action) instead of leaving it
/// visible-but-empty.</summary>
public sealed class BoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language) =>
        value is true ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        value is Visibility.Visible;
}
