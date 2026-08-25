using System.Windows.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Slipstream.Meridian.Controls;

/// <summary>
/// A section-scoped heading: a Title on the leading edge and an optional trailing
/// action (label + command), e.g. "Recent transfers" / "See all". The action is
/// hidden — not just disabled — when either ActionLabel or ActionCommand is unset,
/// so a section with no follow-up action doesn't show a dead-looking link.
/// </summary>
public sealed class MeridianSectionHeader : Control
{
    public MeridianSectionHeader() => DefaultStyleKey = typeof(MeridianSectionHeader);

    public static readonly DependencyProperty TitleProperty = DependencyProperty.Register(
        nameof(Title), typeof(string), typeof(MeridianSectionHeader),
        new PropertyMetadata(null));

    public string? Title
    {
        get => (string?)GetValue(TitleProperty);
        set => SetValue(TitleProperty, value);
    }

    public static readonly DependencyProperty ActionLabelProperty = DependencyProperty.Register(
        nameof(ActionLabel), typeof(string), typeof(MeridianSectionHeader),
        new PropertyMetadata(null, OnActionPropertyChanged));

    public string? ActionLabel
    {
        get => (string?)GetValue(ActionLabelProperty);
        set => SetValue(ActionLabelProperty, value);
    }

    public static readonly DependencyProperty ActionCommandProperty = DependencyProperty.Register(
        nameof(ActionCommand), typeof(ICommand), typeof(MeridianSectionHeader),
        new PropertyMetadata(null, OnActionPropertyChanged));

    public ICommand? ActionCommand
    {
        get => (ICommand?)GetValue(ActionCommandProperty);
        set => SetValue(ActionCommandProperty, value);
    }

    private static readonly DependencyProperty IsActionVisibleProperty = DependencyProperty.Register(
        nameof(IsActionVisible), typeof(bool), typeof(MeridianSectionHeader),
        new PropertyMetadata(false));

    /// <summary>
    /// True only when both ActionLabel and ActionCommand are set. The default template
    /// binds the trailing action button's Visibility to this via a bool-to-Visibility
    /// converter, so an unset action collapses out of layout entirely.
    /// </summary>
    public bool IsActionVisible
    {
        get => (bool)GetValue(IsActionVisibleProperty);
        private set => SetValue(IsActionVisibleProperty, value);
    }

    private static void OnActionPropertyChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var header = (MeridianSectionHeader)d;
        header.IsActionVisible = !string.IsNullOrEmpty(header.ActionLabel) && header.ActionCommand is not null;
    }
}
