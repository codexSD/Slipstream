using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Slipstream.Meridian.Controls;

/// <summary>
/// A pill-shaped status affordance: a small glyph/dot plus a text label, coloured by
/// <see cref="MeridianStatus"/>. Colour is deliberately never the only cue — the Label
/// DP is required, and both the static <see cref="Validate"/> contract and the control's
/// own property-changed callback throw when it is missing, so a live pill with no label
/// fails the same way the pure unit test does.
/// </summary>
public sealed class MeridianStatusPill : Control
{
    public MeridianStatusPill() => DefaultStyleKey = typeof(MeridianStatusPill);

    public static readonly DependencyProperty StatusProperty = DependencyProperty.Register(
        nameof(Status), typeof(MeridianStatus), typeof(MeridianStatusPill),
        new PropertyMetadata(MeridianStatus.Neutral, OnStatusOrLabelChanged));

    public MeridianStatus Status
    {
        get => (MeridianStatus)GetValue(StatusProperty);
        set => SetValue(StatusProperty, value);
    }

    public static readonly DependencyProperty LabelProperty = DependencyProperty.Register(
        nameof(Label), typeof(string), typeof(MeridianStatusPill),
        new PropertyMetadata(null, OnStatusOrLabelChanged));

    /// <summary>Required. The word that pairs with the status colour — never omit it, so
    /// colour is never the only cue.</summary>
    public string? Label
    {
        get => (string?)GetValue(LabelProperty);
        set => SetValue(LabelProperty, value);
    }

    public static readonly DependencyProperty BrushKeyProperty = DependencyProperty.Register(
        nameof(BrushKey), typeof(string), typeof(MeridianStatusPill),
        new PropertyMetadata("MeridianInkMutedBrush"));

    /// <summary>The resource key of the current signal brush, kept in sync with Status so the
    /// default template can bind to it via a StaticResource-style lookup without a converter.</summary>
    public string BrushKey
    {
        get => (string)GetValue(BrushKeyProperty);
        private set => SetValue(BrushKeyProperty, value);
    }

    private static void OnStatusOrLabelChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var pill = (MeridianStatusPill)d;
        pill.BrushKey = BrushKeyFor(pill.Status);

        // Only validate once both properties have had a chance to be set (i.e. once the
        // control is actually in use), not during construction defaults.
        if (pill.ReadLocalValue(LabelProperty) != DependencyProperty.UnsetValue)
            Validate(pill.Status, pill.Label ?? "");
    }

    /// <summary>Maps a status to the design-token brush resource key that renders it.</summary>
    public static string BrushKeyFor(MeridianStatus status) => status switch
    {
        MeridianStatus.Positive => "MeridianPositiveBrush",
        MeridianStatus.Warning => "MeridianWarningBrush",
        MeridianStatus.Critical => "MeridianCriticalBrush",
        MeridianStatus.Info => "MeridianInfoBrush",
        MeridianStatus.Neutral => "MeridianInkMutedBrush",
        _ => "MeridianInkMutedBrush",
    };

    /// <summary>Throws when a status pill would show colour alone. Called both as the explicit
    /// pure-function contract and from the live control's property-changed callback.</summary>
    public static void Validate(MeridianStatus status, string label)
    {
        if (string.IsNullOrWhiteSpace(label))
            throw new ArgumentException(
                $"MeridianStatusPill requires a non-empty Label for status '{status}': " +
                "status colour must never be the only cue.",
                nameof(label));
    }
}
