using System.Windows.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Slipstream.Meridian.Controls;

/// <summary>
/// A ContentControl that switches between Loading / Content / Empty / Error via
/// VisualStateManager — the same four-state contract as the Compose implementation, so
/// the two platforms stay recognisably one system. Empty states name the next action;
/// error text renders in MeridianCriticalBrush.
/// </summary>
public sealed class MeridianStateView : ContentControl
{
    public MeridianStateView() => DefaultStyleKey = typeof(MeridianStateView);

    public static readonly DependencyProperty StateProperty = DependencyProperty.Register(
        nameof(State), typeof(MeridianStateViewState), typeof(MeridianStateView),
        new PropertyMetadata(MeridianStateViewState.Content, OnStateChanged));

    public MeridianStateViewState State
    {
        get => (MeridianStateViewState)GetValue(StateProperty);
        set => SetValue(StateProperty, value);
    }

    public static readonly DependencyProperty MessageProperty = DependencyProperty.Register(
        nameof(Message), typeof(string), typeof(MeridianStateView),
        new PropertyMetadata(null));

    /// <summary>Shown in the Empty or Error visual. Empty states should name the next action;
    /// error text follows the §15 voice.</summary>
    public string? Message
    {
        get => (string?)GetValue(MessageProperty);
        set => SetValue(MessageProperty, value);
    }

    public static readonly DependencyProperty ActionLabelProperty = DependencyProperty.Register(
        nameof(ActionLabel), typeof(string), typeof(MeridianStateView),
        new PropertyMetadata(null));

    public string? ActionLabel
    {
        get => (string?)GetValue(ActionLabelProperty);
        set => SetValue(ActionLabelProperty, value);
    }

    public static readonly DependencyProperty ActionCommandProperty = DependencyProperty.Register(
        nameof(ActionCommand), typeof(ICommand), typeof(MeridianStateView),
        new PropertyMetadata(null));

    public ICommand? ActionCommand
    {
        get => (ICommand?)GetValue(ActionCommandProperty);
        set => SetValue(ActionCommandProperty, value);
    }

    protected override void OnApplyTemplate()
    {
        base.OnApplyTemplate();
        VisualStateManager.GoToState(this, State.ToString(), false);
    }

    private static void OnStateChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var view = (MeridianStateView)d;
        VisualStateManager.GoToState(view, view.State.ToString(), true);
    }
}
