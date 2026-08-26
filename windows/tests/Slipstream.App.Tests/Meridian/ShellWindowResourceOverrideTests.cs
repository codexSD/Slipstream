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
    public void Every_destination_hosts_its_real_view_not_a_placeholder()
    {
        // Regression coverage for review finding #2 (the Transfers destination must host the
        // real TransfersPage, like every other destination) - restated against the mechanism
        // that actually works at runtime.
        //
        // The original form of this check asserted a ContentPresenter/{StaticResource} pattern
        // where each view was stashed in a ResourceDictionary. That pattern cannot work: WinUI
        // flags every ResourceDictionary value as shareable, so assigning one to
        // ContentControl.Content throws E_INVALIDARG and the process dies inside
        // Window.Activate() before a window is ever shown. The views are now constructed and
        // swapped in from the code-behind instead.
        var codeBehind = File.ReadAllText(TestPaths.App("Shell/ShellWindow.xaml.cs"));

        foreach (var (label, view) in new[]
        {
            ("Device", "DevicePage"),
            ("Browse phone", "BrowsePage"),
            ("Transfers", "TransfersPage"),
            ("History", "HistoryPage"),
            ("Settings", "SettingsPage"),
        })
        {
            Assert.Contains($"[\"{label}\"] = new Pages.{view}(", codeBehind);
        }

        Assert.Contains("PageHost.Content =", codeBehind);
        Assert.DoesNotContain("placeholder", LoadSource(), StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void Destination_views_are_never_smuggled_through_a_resource_dictionary()
    {
        // The launch-blocking bug: a UIElement retrieved from a ResourceDictionary cannot be
        // assigned to ContentControl.Content. Neither the shell markup nor its code-behind may
        // reintroduce that pattern.
        var xaml = LoadSource();
        var codeBehind = File.ReadAllText(TestPaths.App("Shell/ShellWindow.xaml.cs"));

        foreach (var key in new[]
        {
            "DevicePageContent",
            "BrowsePageContent",
            "TransfersPageContent",
            "HistoryPageContent",
            "SettingsPageContent",
        })
        {
            Assert.DoesNotContain(key, xaml);
            Assert.DoesNotContain(key, codeBehind);
        }
    }
}
