using System.Xml.Linq;

namespace Slipstream.App.Tests.Meridian;

public class DataGridTests
{
    private static readonly XNamespace XNs = "http://schemas.microsoft.com/winfx/2006/xaml";

    private static XDocument LoadGeneric() =>
        XDocument.Load(TestPaths.Meridian("Themes/Generic.xaml"));

    private static string NameOf(XElement e) =>
        e.Attribute("Name")?.Value ?? e.Attribute(XNs + "Name")?.Value ?? "";

    private static XElement GetDataGridStyle(XDocument doc)
    {
        var style = doc.Descendants()
            .Where(e => e.Name.LocalName == "Style")
            .FirstOrDefault(e => (e.Attribute("TargetType")?.Value ?? "").EndsWith("MeridianDataGrid"));
        Assert.True(style is not null, "No default Style with TargetType MeridianDataGrid found in Generic.xaml");
        return style!;
    }

    [Fact]
    public void Header_row_text_uses_muted_ink_brush()
    {
        var style = GetDataGridStyle(LoadGeneric());

        // Header cell TextBlocks (identified by name or by being inside the header row Grid/Panel)
        // must set Foreground to MeridianInkMutedBrush, either directly or via a Style.
        var headerTextBlocks = style.Descendants()
            .Where(e => e.Name.LocalName == "TextBlock")
            .Where(e => NameOf(e).Contains("Header"))
            .ToList();

        Assert.True(headerTextBlocks.Count > 0, "MeridianDataGrid template must contain a named header TextBlock/element.");

        var usesMutedInk = headerTextBlocks.Any(e =>
            (e.Attribute("Foreground")?.Value ?? "").Contains("MeridianInkMutedBrush"));

        Assert.True(usesMutedInk,
            "MeridianDataGrid header text must reference MeridianInkMutedBrush (directly or via a Style setter).");
    }

    [Fact]
    public void Row_separator_is_a_hairline_bottom_border_with_no_vertical_gridlines()
    {
        var doc = LoadGeneric();
        GetDataGridStyle(doc); // MeridianDataGrid style must exist; separators may live in a
                                // sibling ItemContainerStyle resource it references.

        // Find the row/header container Border elements that set BorderBrush to MeridianStrokeBrush.
        var strokeBorders = doc.Descendants()
            .Where(e => e.Name.LocalName == "Border")
            .Where(e => (e.Attribute("BorderBrush")?.Value ?? "").Contains("MeridianStrokeBrush"))
            .ToList();

        Assert.True(strokeBorders.Count > 0,
            "MeridianDataGrid template must have at least one Border with BorderBrush=MeridianStrokeBrush (row/header separators).");

        // Every stroke border must be a bottom-only 1px border (no vertical/column separators).
        foreach (var border in strokeBorders)
        {
            var thickness = border.Attribute("BorderThickness")?.Value ?? "";
            // Accept either "0,0,0,1" (bottom-only) form.
            Assert.True(thickness == "0,0,0,1",
                $"Row/header separator BorderThickness must be bottom-only (0,0,0,1); found \"{thickness}\".");
        }

        // No explicit vertical/column separator element should exist.
        var columnSeparators = doc.Descendants()
            .Where(e => NameOf(e).Contains("ColumnSeparator", StringComparison.OrdinalIgnoreCase));
        Assert.Empty(columnSeparators);
    }

    [Fact]
    public void Selected_row_background_uses_tint_brush()
    {
        var doc = LoadGeneric();
        GetDataGridStyle(doc);

        // The ItemContainerStyle for ListViewItem should set the Selected state background to
        // MeridianTintBrush, either as a VisualState setter or a resource override.
        var text = doc.ToString();
        Assert.Contains("MeridianTintBrush", text);

        // A "Selected" VisualState must exist, driving some named element's Opacity/Background...
        var hasSelectedState = doc.Descendants()
            .Where(e => e.Name.LocalName == "VisualState")
            .Any(e => NameOf(e).Contains("Selected"));
        Assert.True(hasSelectedState, "MeridianDataGrid's ItemContainerStyle must declare a Selected VisualState.");

        // ...and the row template's selection layer (Rectangle/Border) must be filled with
        // MeridianTintBrush, either directly (Setter Property=Background/Fill) or via a Setter
        // targeting Fill/Background elsewhere driven by that Selected state.
        var usesTint = doc.Descendants()
            .Where(e => e.Name.LocalName is "Rectangle" or "Border")
            .Any(e => (e.Attribute("Fill")?.Value ?? e.Attribute("Background")?.Value ?? "")
                .Contains("MeridianTintBrush"));

        Assert.True(usesTint,
            "MeridianDataGrid's selected-row background must reference MeridianTintBrush.");
    }

    [Fact]
    public void Row_container_min_height_is_at_least_44()
    {
        var doc = LoadGeneric();
        GetDataGridStyle(doc);

        var setters = doc.Descendants()
            .Where(e => e.Name.LocalName == "Setter")
            .Where(e => (e.Attribute("Property")?.Value ?? "") == "MinHeight")
            .ToList();

        Assert.True(setters.Count > 0, "MeridianDataGrid template must set MinHeight on the row container.");
        Assert.True(setters.All(s => double.Parse(s.Attribute("Value")!.Value) >= 44),
            "MeridianDataGrid row MinHeight must be >= 44 to satisfy the tap-target constraint.");
    }

    [Fact]
    public void Generic_xaml_declares_a_default_style_for_MeridianDataGrid()
    {
        var doc = LoadGeneric();
        GetDataGridStyle(doc); // throws/asserts if missing
    }

    /// <summary>
    /// MeridianDataGrid.BuildRowContent builds row-cell TextBlocks entirely in code (see the
    /// class remarks in MeridianDataGrid.cs) rather than via a static XAML DataTemplate, because
    /// columns are data-driven at runtime. That code-behind logic can't be exercised headlessly
    /// here (instantiating WinUI controls off the UI thread throws COMException, per the Task 5
    /// finding), so this test falls back to source inspection: it parses the source file's own
    /// text to confirm the tabular-numeral-alignment call lives inside the IsTabular branch,
    /// alongside the TextAlignment.Right assignment it must accompany. Rate/ETA/size/percent
    /// columns rendered through this path need fixed-width digits or they visibly jitter as
    /// values update (see MeridianTypographyTests for the equivalent XAML-declared styles).
    /// </summary>
    [Fact]
    public void BuildRowContent_applies_tabular_numeral_alignment_to_tabular_columns()
    {
        var path = TestPaths.Meridian("Controls/MeridianDataGrid.cs");
        Assert.True(File.Exists(path), $"MeridianDataGrid.cs not found at {path}");
        var source = File.ReadAllText(path);

        var methodStart = source.IndexOf("BuildRowContent", StringComparison.Ordinal);
        Assert.True(methodStart >= 0, "BuildRowContent method not found in MeridianDataGrid.cs");

        var tabularBranchStart = source.IndexOf("column.IsTabular", methodStart, StringComparison.Ordinal);
        Assert.True(tabularBranchStart >= 0,
            "BuildRowContent must branch on column.IsTabular to apply tabular-specific formatting.");

        // The numeral-alignment call must appear after the IsTabular check within the same
        // method body (a generous window past the branch keeps this robust to minor reordering
        // without matching unrelated code elsewhere in the file).
        var window = source.Substring(tabularBranchStart, Math.Min(2000, source.Length - tabularBranchStart));

        Assert.Contains("SetNumeralAlignment", window);
        Assert.Contains("FontNumeralAlignment.Tabular", window);
        Assert.Contains("TextAlignment.Right", window);
    }
}
