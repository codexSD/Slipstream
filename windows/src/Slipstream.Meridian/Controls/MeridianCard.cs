using Microsoft.UI.Xaml.Controls;

namespace Slipstream.Meridian.Controls;

/// <summary>
/// The atom of every screen: Surface fill, lg radius, 1px stroke, no elevation.
/// Structure comes from the stroke — Meridian has no shadow language, so the default
/// style deliberately sets no ThemeShadow and no Translation.
/// Carries no content padding: inset belongs to the caller's content, so one card
/// style wraps either a padded StackPanel or a Grid without fighting it.
/// </summary>
public sealed class MeridianCard : ContentControl
{
    public MeridianCard() => DefaultStyleKey = typeof(MeridianCard);
}
