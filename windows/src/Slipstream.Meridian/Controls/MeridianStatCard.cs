using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Slipstream.Meridian.Controls;

/// <summary>
/// A small muted label above a large bold tabular number, on the same Surface/Stroke/lg-radius
/// visual language as MeridianCard. Used for compact secondary metrics (total transferred,
/// files remaining) alongside the screen's single MeridianHeroMetric.
/// </summary>
public sealed class MeridianStatCard : Control
{
    public MeridianStatCard() => DefaultStyleKey = typeof(MeridianStatCard);

    public static readonly DependencyProperty LabelProperty = DependencyProperty.Register(
        nameof(Label), typeof(string), typeof(MeridianStatCard),
        new PropertyMetadata(null));

    public string? Label
    {
        get => (string?)GetValue(LabelProperty);
        set => SetValue(LabelProperty, value);
    }

    public static readonly DependencyProperty ValueProperty = DependencyProperty.Register(
        nameof(Value), typeof(string), typeof(MeridianStatCard),
        new PropertyMetadata(null));

    /// <summary>Pre-formatted by the caller (e.g. "128 GB", "42 files").</summary>
    public string? Value
    {
        get => (string?)GetValue(ValueProperty);
        set => SetValue(ValueProperty, value);
    }
}
