using Microsoft.UI.Xaml;

namespace Slipstream.Meridian.Controls;

/// <summary>
/// Horizontal alignment for a MeridianDataGrid column's cell content. Left/Center map directly
/// to HorizontalAlignment; Right is used explicitly (and implied by IsTabular) for numeric
/// columns so figures line up on their trailing edge.
/// </summary>
public enum MeridianDataGridColumnAlignment
{
    Left,
    Center,
    Right,
}

/// <summary>
/// Declarative description of one MeridianDataGrid column. Not a DependencyObject — columns are
/// plain data so callers can build a Columns collection in code-behind or a view model without
/// touching XAML. MeridianDataGrid turns each column into a cell renderer at row-generation time
/// (see MeridianDataGrid.BuildRowContent), keyed off Binding, a simple property-path string
/// resolved via reflection against the row's data item — no per-column DataTemplate authoring
/// required from callers.
/// </summary>
public sealed class MeridianDataGridColumn
{
    /// <summary>Sentence-case header text (never ALL CAPS — the muted-header contract).</summary>
    public string Header { get; set; } = string.Empty;

    /// <summary>
    /// Property path on the row's data item, resolved via reflection (e.g. "Name" or
    /// "Owner.DisplayName"). Kept as a plain string rather than a compiled Binding so this class
    /// has no XAML/x:Bind dependency and callers can build Columns entirely in code.
    /// </summary>
    public string Binding { get; set; } = string.Empty;

    /// <summary>Column width. Star/Auto/Pixel via GridLength, same vocabulary as a Grid column.</summary>
    public GridLength Width { get; set; } = new GridLength(1, GridUnitType.Star);

    /// <summary>Cell text alignment. Ignored (forced to Right) when IsTabular is true.</summary>
    public MeridianDataGridColumnAlignment Alignment { get; set; } = MeridianDataGridColumnAlignment.Left;

    /// <summary>
    /// When true, cell text gets Typography.NumeralAlignment="Tabular" and right alignment, so
    /// size/rate columns line up on their digits regardless of Alignment.
    /// </summary>
    public bool IsTabular { get; set; }
}
