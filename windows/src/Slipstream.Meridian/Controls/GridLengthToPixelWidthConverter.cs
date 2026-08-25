using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Data;

namespace Slipstream.Meridian.Controls;

/// <summary>
/// Converts a MeridianDataGridColumn's GridLength Width to a pixel double for the header cell's
/// Width property, using the same fallback as MeridianDataGrid's code-generated row cells
/// (MeridianDataGrid.ColumnWidthToPixels) so header and row cells for a given column stay the
/// same width.
/// </summary>
public sealed class GridLengthToPixelWidthConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language) =>
        value is GridLength gridLength ? MeridianDataGrid.ColumnWidthToPixels(gridLength) : 140d;

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}
