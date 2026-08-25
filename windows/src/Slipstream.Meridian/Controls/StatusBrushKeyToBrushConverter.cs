using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Media;

namespace Slipstream.Meridian.Controls;

/// <summary>Resolves a Meridian brush resource key (e.g. "MeridianPositiveBrush", produced by
/// <see cref="MeridianStatusPill.BrushKeyFor"/>) into the actual themed brush, so the default
/// template can react to theme changes without a per-status style fork.</summary>
public sealed class StatusBrushKeyToBrushConverter : IValueConverter
{
    public object? Convert(object value, Type targetType, object parameter, string language)
    {
        if (value is string key && Application.Current.Resources.TryGetValue(key, out var resource) && resource is Brush brush)
            return brush;

        return null;
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}
