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

    [Fact]
    public void Nav_selection_overrides_live_inside_ThemeDictionaries_so_they_follow_a_theme_switch()
    {
        // Regression coverage for review finding #6: a plain ListView.Resources entry resolves
        // {StaticResource} once at parse time and never re-resolves on a live theme change.
        // Every ListViewItemBackgroundSelected/etc. re-key MUST sit inside a
        // ResourceDictionary.ThemeDictionaries "Light"/"Dark" entry (mirroring App.xaml's own
        // structure), never as a direct child of ListView.Resources.
        var doc = LoadDocument();
        XNamespace xaml = "http://schemas.microsoft.com/winfx/2006/xaml";

        var themeDictionaries = doc.Descendants()
            .Where(e => e.Name.LocalName == "ResourceDictionary.ThemeDictionaries")
            .ToList();

        Assert.True(themeDictionaries.Count > 0,
            "Expected ShellWindow.xaml to override the nav-pill selection brushes inside a " +
            "ResourceDictionary.ThemeDictionaries structure, not a plain resource dictionary.");

        var themeKeys = themeDictionaries
            .SelectMany(td => td.Descendants().Where(e => e.Name.LocalName == "StaticResource"))
            .Select(e => e.Attribute(xaml + "Key")?.Value)
            .ToHashSet();

        foreach (var key in new[]
        {
            "ListViewItemBackgroundSelected",
            "ListViewItemBackgroundSelectedPointerOver",
            "ListViewItemBackgroundSelectedPressed",
            "ListViewItemForegroundSelected",
        })
        {
            Assert.Contains(key, themeKeys);
        }

        // And each theme entry ("Light", "Dark") must define all four keys — a theme with a
        // stale/missing override would silently fall back to the default (unselected) brush
        // the moment the user switches to it.
        foreach (var themeDict in themeDictionaries.SelectMany(td => td.Elements()))
        {
            var keysInThisTheme = themeDict.Descendants()
                .Where(e => e.Name.LocalName == "StaticResource")
                .Select(e => e.Attribute(xaml + "Key")?.Value)
                .ToHashSet();

            Assert.Equal(4, keysInThisTheme.Count);
        }
    }

    [Fact]
    public void Nav_ListViewItem_meets_the_44px_tap_target_floor()
    {
        // Regression coverage for review finding #7: MinHeight="0" strips the default
        // ListViewItem height, leaving a ~39px row below the 44px tap-target floor.
        var xaml = LoadSource();

        Assert.DoesNotContain(@"Setter Property=""MinHeight"" Value=""0""", xaml);
        Assert.Contains(@"Setter Property=""MinHeight"" Value=""44""", xaml);
    }

    [Fact]
    public void Transfers_destination_hosts_the_real_TransfersPage_not_a_placeholder()
    {
        // Regression coverage for review finding #2: the Transfers destination must use the
        // same ContentPresenter/StaticResource pattern as every other destination (Device,
        // Browse phone, History, Settings), not a stand-in TextBlock.
        var doc = LoadDocument();

        var transfersTemplate = doc.Descendants()
            .FirstOrDefault(e => e.Name.LocalName == "DestinationTemplateSelector.TransfersTemplate");

        Assert.True(transfersTemplate is not null, "Expected a TransfersTemplate in ShellWindow.xaml.");

        var presenter = transfersTemplate!.Descendants()
            .FirstOrDefault(e => e.Name.LocalName == "ContentPresenter");

        Assert.True(presenter is not null,
            "Transfers destination must host TransfersPageContent via ContentPresenter, like every other destination.");
        Assert.Contains("TransfersPageContent", presenter!.Attribute("Content")?.Value ?? string.Empty);

        Assert.DoesNotContain("placeholder", LoadSource(), StringComparison.OrdinalIgnoreCase);
    }
}
