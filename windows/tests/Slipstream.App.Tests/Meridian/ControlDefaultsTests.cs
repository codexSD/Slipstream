using System.Xml.Linq;

namespace Slipstream.App.Tests.Meridian;

public class ControlDefaultsTests
{
    private static XDocument LoadGeneric() =>
        XDocument.Load(TestPaths.Meridian("Themes/Generic.xaml"));

    /// <summary>Parse default control styles keyed by their (unqualified) TargetType.</summary>
    private static Dictionary<string, List<(string property, string value)>> ExtractStylesByTargetType(XDocument doc)
    {
        var styles = new Dictionary<string, List<(string, string)>>();

        foreach (var style in doc.Descendants().Where(e => e.Name.LocalName == "Style"))
        {
            var targetType = style.Attribute("TargetType")?.Value;
            if (targetType is null) continue;

            // TargetType is typically "local:MeridianCard" — strip the xmlns prefix.
            var shortName = targetType.Contains(':') ? targetType[(targetType.IndexOf(':') + 1)..] : targetType;

            var setters = new List<(string, string)>();
            foreach (var setter in style.Descendants().Where(e => e.Name.LocalName == "Setter"))
            {
                var property = setter.Attribute("Property")?.Value;
                var value = setter.Attribute("Value")?.Value;
                if (property is not null && value is not null)
                    setters.Add((property, value));
            }

            styles[shortName] = setters;
        }

        return styles;
    }

    [Theory]
    [InlineData("MeridianCard", "MeridianRadiusLg")]
    [InlineData("MeridianIconTile", "MeridianRadiusSm")]
    [InlineData("MeridianStatCard", "MeridianRadiusLg")]
    public void Surface_control_default_style_has_stroke_and_surface_fill(string controlName, string expectedRadiusKey)
    {
        var styles = ExtractStylesByTargetType(LoadGeneric());
        Assert.True(styles.ContainsKey(controlName), $"No default Style with TargetType matching {controlName} found in Generic.xaml");

        var setters = styles[controlName].ToDictionary(s => s.property, s => s.value);

        Assert.True(setters.ContainsKey("BorderThickness"), $"{controlName} missing BorderThickness setter");
        Assert.Equal("1", setters["BorderThickness"]);

        Assert.True(setters.ContainsKey("BorderBrush"), $"{controlName} missing BorderBrush setter");
        Assert.Equal("{ThemeResource MeridianStrokeBrush}", setters["BorderBrush"]);

        Assert.True(setters.ContainsKey("Background"), $"{controlName} missing Background setter");
        Assert.Equal(controlName == "MeridianIconTile"
                ? "{ThemeResource MeridianTintBrush}"
                : "{ThemeResource MeridianSurfaceBrush}",
            setters["Background"]);

        Assert.True(setters.ContainsKey("CornerRadius"), $"{controlName} missing CornerRadius setter");
        Assert.Equal($"{{StaticResource {expectedRadiusKey}}}", setters["CornerRadius"]);
    }

    [Fact]
    public void MeridianStatCard_value_text_uses_the_tabular_stat_value_style()
    {
        var doc = LoadGeneric();
        var statCardStyle = doc.Descendants()
            .Where(e => e.Name.LocalName == "Style")
            .FirstOrDefault(e => (e.Attribute("TargetType")?.Value ?? "").EndsWith("MeridianStatCard"));
        Assert.True(statCardStyle is not null, "No default Style with TargetType MeridianStatCard found in Generic.xaml");

        var usesStatValueStyle = statCardStyle!.Descendants()
            .Where(e => e.Name.LocalName == "TextBlock")
            .Any(e => (e.Attribute("Style")?.Value ?? "").Contains("MeridianStatValueStyle"));
        Assert.True(usesStatValueStyle,
            "MeridianStatCard's value TextBlock must use a tabular-numeral style (MeridianStatValueStyle).");
    }

    [Fact]
    public void MeridianHeroMetric_uses_the_hero_metric_style()
    {
        var doc = LoadGeneric();
        var heroStyle = doc.Descendants()
            .Where(e => e.Name.LocalName == "Style")
            .FirstOrDefault(e => (e.Attribute("TargetType")?.Value ?? "").EndsWith("MeridianHeroMetric"));
        Assert.True(heroStyle is not null, "No default Style with TargetType MeridianHeroMetric found in Generic.xaml");

        var usesHeroMetricStyle = heroStyle!.Descendants()
            .Where(e => e.Name.LocalName == "TextBlock")
            .Any(e => (e.Attribute("Style")?.Value ?? "").Contains("MeridianHeroMetricStyle"));
        Assert.True(usesHeroMetricStyle,
            "MeridianHeroMetric's value TextBlock must use MeridianHeroMetricStyle (40sp/Bold/Tabular/Brand).");
    }

    [Fact]
    public void No_default_style_sets_elevation_or_shadow()
    {
        var doc = LoadGeneric();

        var shadowElements = doc.Descendants()
            .Where(e => e.Name.LocalName is "ThemeShadow" or "DropShadow")
            .ToList();
        Assert.True(shadowElements.Count == 0,
            "Generic.xaml declares a ThemeShadow/DropShadow element; Meridian uses strokes, not shadows.");

        foreach (var setter in doc.Descendants().Where(e => e.Name.LocalName == "Setter"))
        {
            var property = setter.Attribute("Property")?.Value;
            var value = setter.Attribute("Value")?.Value;
            if (property is null) continue;

            Assert.False(property is "Shadow" or "Translation" && value is not null && value != "0,0,0",
                $"Setter for {property}=\"{value}\" introduces elevation; Meridian uses strokes, not shadows.");
        }
    }

    [Fact]
    public void MeridianIconTile_default_tap_target_is_at_least_44px()
    {
        var styles = ExtractStylesByTargetType(LoadGeneric());
        Assert.True(styles.ContainsKey("MeridianIconTile"), "No default Style for MeridianIconTile found in Generic.xaml");

        var setters = styles["MeridianIconTile"].ToDictionary(s => s.property, s => s.value);

        Assert.True(setters.ContainsKey("MinWidth"), "MeridianIconTile default style missing MinWidth");
        Assert.True(double.Parse(setters["MinWidth"]) >= 44,
            "MeridianIconTile MinWidth must be >= 44 to satisfy the tap-target constraint");

        Assert.True(setters.ContainsKey("MinHeight"), "MeridianIconTile default style missing MinHeight");
        Assert.True(double.Parse(setters["MinHeight"]) >= 44,
            "MeridianIconTile MinHeight must be >= 44 to satisfy the tap-target constraint");
    }

    [Fact]
    public void Generic_xaml_declares_default_styles_for_all_three_controls()
    {
        var styles = ExtractStylesByTargetType(LoadGeneric());

        foreach (var name in new[] { "MeridianCard", "MeridianSectionHeader", "MeridianIconTile" })
        {
            Assert.True(styles.ContainsKey(name), $"Generic.xaml is missing a default Style for {name}");
        }
    }
}
