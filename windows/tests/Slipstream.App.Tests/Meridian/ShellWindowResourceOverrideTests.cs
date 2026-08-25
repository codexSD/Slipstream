using System.Text.RegularExpressions;
using System.Xml.Linq;

namespace Slipstream.App.Tests.Meridian;

/// <summary>
/// Regression coverage for a resource-type mismatch bug: the nav-pill selection overrides in
/// ShellWindow.xaml must re-key an existing Brush resource (via StaticResource/ResourceKey),
/// not wrap it in a new SolidColorBrush whose Color= property expects a Windows.UI.Color, not a
/// Brush. The broken form (`&lt;SolidColorBrush ... Color="{ThemeResource MeridianXBrush}" /&gt;`)
/// type-checks past the XAML compiler but throws at runtime the first time the ListView's
/// resources materialize.
/// </summary>
public class ShellWindowResourceOverrideTests
{
    private static string LoadSource() =>
        File.ReadAllText(TestPaths.App("Shell/ShellWindow.xaml"));

    private static XDocument LoadDocument() =>
        XDocument.Load(TestPaths.App("Shell/ShellWindow.xaml"));

    [Fact]
    public void ShellWindow_xaml_never_assigns_a_ThemeResource_brush_to_a_Color_attribute()
    {
        var xaml = LoadSource();

        // Color="{ThemeResource SomeXBrush}" is a resource-type mismatch: Color expects a
        // Windows.UI.Color, but every "*Brush" token resource is a SolidColorBrush.
        var badPattern = new Regex(@"Color\s*=\s*""\{ThemeResource\s+\w*Brush\}""");

        Assert.False(badPattern.IsMatch(xaml),
            "ShellWindow.xaml assigns a Brush-typed ThemeResource to a Color attribute. " +
            "This is a resource-type mismatch (SolidColorBrush.Color expects a Windows.UI.Color) " +
            "that will throw at runtime when the ListView's resources materialize. Re-key the " +
            "brush instead, e.g. <StaticResource x:Key=\"...\" ResourceKey=\"MeridianBrandBrush\" />.");
    }

    [Theory]
    [InlineData("ListViewItemBackgroundSelected", "MeridianBrandBrush")]
    [InlineData("ListViewItemBackgroundSelectedPointerOver", "MeridianBrandBrush")]
    [InlineData("ListViewItemBackgroundSelectedPressed", "MeridianBrandStrongBrush")]
    [InlineData("ListViewItemForegroundSelected", "MeridianOnBrandBrush")]
    public void Nav_selection_overrides_rekey_the_brand_brush_via_StaticResource(string key, string expectedResourceKey)
    {
        var doc = LoadDocument();

        var rekey = doc.Descendants()
            .Where(e => e.Name.LocalName == "StaticResource")
            .FirstOrDefault(e => e.Attribute(XName.Get("Key", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value == key);

        Assert.True(rekey is not null,
            $"Expected a <StaticResource x:Key=\"{key}\" ResourceKey=\"...\" /> re-keying entry in ShellWindow.xaml.");

        Assert.Equal(expectedResourceKey, rekey!.Attribute("ResourceKey")?.Value);
    }
}
