using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Slipstream.Meridian.Controls;

/// <summary>
/// The one-per-screen hero number — 40sp, bold, tabular, Brand-coloured — rendered via
/// MeridianHeroMetricStyle. On the Slipstream Windows app this is the live transfer rate.
/// </summary>
public sealed class MeridianHeroMetric : Control
{
    public MeridianHeroMetric() => DefaultStyleKey = typeof(MeridianHeroMetric);

    public static readonly DependencyProperty ValueProperty = DependencyProperty.Register(
        nameof(Value), typeof(string), typeof(MeridianHeroMetric),
        new PropertyMetadata(null));

    /// <summary>Pre-formatted by the caller (e.g. "42.3 MB/s").</summary>
    public string? Value
    {
        get => (string?)GetValue(ValueProperty);
        set => SetValue(ValueProperty, value);
    }
}
