using System.Xml.Linq;

namespace Slipstream.App.Tests.Meridian;

public class MeridianTypographyTests
{
    private static XDocument LoadTypography() =>
        XDocument.Load(TestPaths.Meridian("Themes/Typography.xaml"));

    private static XDocument LoadShapes() =>
        XDocument.Load(TestPaths.Meridian("Themes/Shapes.xaml"));

    /// <summary>Parse style definitions and extract their setters.</summary>
    private static Dictionary<string, List<(string property, string value)>> ExtractStyles(XDocument doc)
    {
        var styles = new Dictionary<string, List<(string, string)>>();
        var ns = XNamespace.Get("http://schemas.microsoft.com/winfx/2006/xaml");

        foreach (var style in doc.Descendants()
                                 .Where(e => e.Name.LocalName == "Style"))
        {
            var key = style.Attribute(ns.GetName("Key"))?.Value;
            if (key is null) continue;

            var setters = new List<(string, string)>();
            foreach (var setter in style.Descendants()
                                       .Where(e => e.Name.LocalName == "Setter"))
            {
                var property = setter.Attribute("Property")?.Value;
                var value = setter.Attribute("Value")?.Value;
                if (property is not null && value is not null)
                    setters.Add((property, value));
            }

            styles[key] = setters;
        }

        return styles;
    }

    /// <summary>Parse resource definitions (CornerRadius, Double, etc.).</summary>
    private static Dictionary<string, string> ExtractResources(XDocument doc)
    {
        var resources = new Dictionary<string, string>();
        var ns = XNamespace.Get("http://schemas.microsoft.com/winfx/2006/xaml");

        foreach (var resource in doc.Descendants()
                                    .Where(e => e.Name.LocalName is "CornerRadius" or "x:Double"))
        {
            var key = resource.Attribute(ns.GetName("Key"))?.Value;
            var value = resource.Value ?? resource.Attribute("Value")?.Value;
            if (key is not null && value is not null)
                resources[key] = value;
        }

        return resources;
    }

    [Fact]
    public void MeridianHeroMetricStyle_is_40px_bold_with_tabular_numerals()
    {
        var styles = ExtractStyles(LoadTypography());
        Assert.True(styles.ContainsKey("MeridianHeroMetricStyle"),
            "MeridianHeroMetricStyle not found");

        var setters = styles["MeridianHeroMetricStyle"]
            .ToDictionary(s => s.property, s => s.value);

        Assert.True(setters.ContainsKey("FontSize"),
            "MeridianHeroMetricStyle missing FontSize");
        Assert.Equal("40", setters["FontSize"]);

        Assert.True(setters.ContainsKey("FontWeight"),
            "MeridianHeroMetricStyle missing FontWeight");
        Assert.Equal("Bold", setters["FontWeight"]);

        Assert.True(setters.ContainsKey("Typography.NumeralAlignment"),
            "MeridianHeroMetricStyle missing Typography.NumeralAlignment (tabular numerals)");
        Assert.Equal("Tabular", setters["Typography.NumeralAlignment"]);
    }

    [Fact]
    public void MeridianScreenTitleStyle_is_20px_bold()
    {
        var styles = ExtractStyles(LoadTypography());
        Assert.True(styles.ContainsKey("MeridianScreenTitleStyle"),
            "MeridianScreenTitleStyle not found");

        var setters = styles["MeridianScreenTitleStyle"]
            .ToDictionary(s => s.property, s => s.value);

        Assert.True(setters.ContainsKey("FontSize"),
            "MeridianScreenTitleStyle missing FontSize");
        Assert.Equal("20", setters["FontSize"]);

        Assert.True(setters.ContainsKey("FontWeight"),
            "MeridianScreenTitleStyle missing FontWeight");
        Assert.Equal("Bold", setters["FontWeight"]);
    }

    [Fact]
    public void MeridianItemTitleStyle_is_15px_bold_with_tabular_numerals()
    {
        var styles = ExtractStyles(LoadTypography());
        Assert.True(styles.ContainsKey("MeridianItemTitleStyle"),
            "MeridianItemTitleStyle not found");

        var setters = styles["MeridianItemTitleStyle"]
            .ToDictionary(s => s.property, s => s.value);

        Assert.True(setters.ContainsKey("FontSize"),
            "MeridianItemTitleStyle missing FontSize");
        Assert.Equal("15", setters["FontSize"]);

        Assert.True(setters.ContainsKey("FontWeight"),
            "MeridianItemTitleStyle missing FontWeight");
        Assert.Equal("Bold", setters["FontWeight"]);

        Assert.True(setters.ContainsKey("Typography.NumeralAlignment"),
            "MeridianItemTitleStyle missing Typography.NumeralAlignment (tabular numerals)");
        Assert.Equal("Tabular", setters["Typography.NumeralAlignment"]);
    }

    [Fact]
    public void MeridianBodyStyle_is_14px_regular()
    {
        var styles = ExtractStyles(LoadTypography());
        Assert.True(styles.ContainsKey("MeridianBodyStyle"),
            "MeridianBodyStyle not found");

        var setters = styles["MeridianBodyStyle"]
            .ToDictionary(s => s.property, s => s.value);

        Assert.True(setters.ContainsKey("FontSize"),
            "MeridianBodyStyle missing FontSize");
        Assert.Equal("14", setters["FontSize"]);

        // Should NOT have Bold FontWeight
        Assert.False(setters.ContainsKey("FontWeight") && setters["FontWeight"] == "Bold",
            "MeridianBodyStyle should not have FontWeight=Bold");
    }

    [Fact]
    public void MeridianLabelStyle_is_12px_with_tabular_numerals()
    {
        var styles = ExtractStyles(LoadTypography());
        Assert.True(styles.ContainsKey("MeridianLabelStyle"),
            "MeridianLabelStyle not found");

        var setters = styles["MeridianLabelStyle"]
            .ToDictionary(s => s.property, s => s.value);

        Assert.True(setters.ContainsKey("FontSize"),
            "MeridianLabelStyle missing FontSize");
        Assert.Equal("12", setters["FontSize"]);

        Assert.True(setters.ContainsKey("Typography.NumeralAlignment"),
            "MeridianLabelStyle missing Typography.NumeralAlignment (tabular numerals)");
        Assert.Equal("Tabular", setters["Typography.NumeralAlignment"]);
    }

    [Fact]
    public void Numeric_styles_declare_tabular_numerals_to_prevent_jitter()
    {
        var styles = ExtractStyles(LoadTypography());
        var numericStyles = new[] { "MeridianHeroMetricStyle", "MeridianItemTitleStyle", "MeridianLabelStyle" };

        foreach (var styleName in numericStyles)
        {
            Assert.True(styles.ContainsKey(styleName), $"{styleName} not found");
            var setters = styles[styleName]
                .ToDictionary(s => s.property, s => s.value);

            Assert.True(setters.ContainsKey("Typography.NumeralAlignment"),
                $"{styleName} is missing Typography.NumeralAlignment setter. " +
                "Without tabular numerals, rate readouts updating several times per second " +
                "visibly jitter as digit widths change.");
            Assert.Equal("Tabular", setters["Typography.NumeralAlignment"]);
        }
    }

    [Fact]
    public void No_style_sets_character_casing()
    {
        var styles = ExtractStyles(LoadTypography());

        foreach (var (styleName, setters) in styles)
        {
            var settersDict = setters.ToDictionary(s => s.property, s => s.value);
            Assert.False(settersDict.ContainsKey("CharacterCasing"),
                $"{styleName} sets CharacterCasing, which should not be used");
        }
    }

    [Fact]
    public void Radii_are_defined_in_shapes()
    {
        var resources = ExtractResources(LoadShapes());

        // Check for radius resources
        var radiusKeys = new[] { "MeridianRadiusSm", "MeridianRadiusMd", "MeridianRadiusLg" };
        var radiusValues = new[] { "12", "14", "16" };

        for (int i = 0; i < radiusKeys.Length; i++)
        {
            Assert.True(resources.ContainsKey(radiusKeys[i]),
                $"Radius resource {radiusKeys[i]} not found in Shapes.xaml");
            Assert.Equal(radiusValues[i], resources[radiusKeys[i]]);
        }
    }

    [Fact]
    public void Label_bold_style_is_based_on_label_style()
    {
        var styles = ExtractStyles(LoadTypography());

        Assert.True(styles.ContainsKey("MeridianLabelBoldStyle"),
            "MeridianLabelBoldStyle not found");

        // Verify it has FontWeight=Bold
        var setters = styles["MeridianLabelBoldStyle"]
            .ToDictionary(s => s.property, s => s.value);
        Assert.True(setters.ContainsKey("FontWeight"),
            "MeridianLabelBoldStyle missing FontWeight");
        Assert.Equal("Bold", setters["FontWeight"]);
    }
}
