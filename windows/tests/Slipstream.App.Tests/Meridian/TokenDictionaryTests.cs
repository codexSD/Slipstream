using System.Xml.Linq;

namespace Slipstream.App.Tests.Meridian;

public class TokenDictionaryTests
{
    private static readonly string[] Roles =
    [
        "Canvas", "Surface", "Stroke", "Tint", "Ink", "InkMuted",
        "Brand", "BrandStrong", "OnBrand", "OnBrandMuted", "Strong",
        "Positive", "Warning", "Critical", "Info",
    ];

    /// <summary>WinUI system brushes an unmapped control reaches for.</summary>
    private static readonly string[] SystemOverrides =
    [
        "AccentFillColorDefaultBrush", "AccentFillColorSecondaryBrush",
        "TextFillColorPrimaryBrush", "TextFillColorSecondaryBrush",
        "ControlFillColorDefaultBrush", "ControlStrokeColorDefaultBrush",
        "LayerFillColorDefaultBrush", "SolidBackgroundFillColorBaseBrush",
        "SystemControlHighlightAccentBrush",
    ];

    private static XDocument Load(string mode) =>
        XDocument.Load(TestPaths.Meridian($"Themes/Tokens.{mode}.xaml"));

    private static HashSet<string> KeysIn(XDocument doc) =>
        doc.Descendants()
           .Select(e => e.Attribute(XName.Get("Key", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value)
           .Where(v => v is not null)
           .ToHashSet()!;

    [Theory]
    [InlineData("Light")]
    [InlineData("Dark")]
    public void Defines_every_meridian_role(string mode)
    {
        var keys = KeysIn(Load(mode));
        var missing = Roles.Where(r => !keys.Contains($"Meridian{r}Brush")).ToList();

        Assert.True(missing.Count == 0, $"{mode} is missing: {string.Join(", ", missing)}");
    }

    [Theory]
    [InlineData("Light")]
    [InlineData("Dark")]
    public void Overrides_every_system_brush_a_stock_control_reaches_for(string mode)
    {
        var keys = KeysIn(Load(mode));
        var missing = SystemOverrides.Where(s => !keys.Contains(s)).ToList();

        Assert.True(missing.Count == 0,
            $"{mode} leaves these to the system Fluent palette: {string.Join(", ", missing)}");
    }

    [Fact]
    public void Light_and_dark_define_exactly_the_same_keys()
    {
        var light = KeysIn(Load("Light"));
        var dark = KeysIn(Load("Dark"));

        Assert.True(light.SetEquals(dark),
            $"Only in Light: {string.Join(", ", light.Except(dark))}. " +
            $"Only in Dark: {string.Join(", ", dark.Except(light))}.");
    }

    [Fact]
    public void Info_equals_brand_and_strong_equals_ink_in_both_modes()
    {
        foreach (var mode in new[] { "Light", "Dark" })
        {
            var colours = ColourMap(Load(mode));
            Assert.Equal(colours["MeridianBrandBrush"], colours["MeridianInfoBrush"]);
            Assert.Equal(colours["MeridianInkBrush"], colours["MeridianStrongBrush"]);
        }
    }

    [Fact]
    public void Light_brand_matches_the_pinned_palette()
    {
        Assert.Equal("#FF1B62C9", ColourMap(Load("Light"))["MeridianBrandBrush"], ignoreCase: true);
    }

    private static Dictionary<string, string> ColourMap(XDocument doc) =>
        doc.Descendants()
           .Where(e => e.Name.LocalName == "SolidColorBrush")
           .ToDictionary(
               e => e.Attribute(XName.Get("Key", "http://schemas.microsoft.com/winfx/2006/xaml"))!.Value,
               e => Normalise(e.Attribute("Color")!.Value));

    private static string Normalise(string colour) =>
        colour.Length == 7 ? "#FF" + colour[1..] : colour;
}
