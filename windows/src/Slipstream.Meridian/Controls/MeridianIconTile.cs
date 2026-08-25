using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Slipstream.Meridian.Controls;

/// <summary>
/// A Tint-filled, sm-radius glyph tile — the compact, tappable icon-plus-label unit used
/// for quick actions. Derives from Button so Command/Click, keyboard invocation, and focus
/// visuals come for free; MeridianIconTile only adds the glyph/label/size vocabulary on top.
/// The default style pins MinWidth/MinHeight to 44 independently of TileSize, so the tap
/// target never falls under the platform's minimum even if a caller shrinks TileSize.
/// </summary>
public sealed class MeridianIconTile : Button
{
    public MeridianIconTile() => DefaultStyleKey = typeof(MeridianIconTile);

    public static readonly DependencyProperty GlyphProperty = DependencyProperty.Register(
        nameof(Glyph), typeof(string), typeof(MeridianIconTile),
        new PropertyMetadata(null));

    /// <summary>A Segoe Fluent Icons glyph codepoint (e.g. ""), rendered via FontIcon.</summary>
    public string? Glyph
    {
        get => (string?)GetValue(GlyphProperty);
        set => SetValue(GlyphProperty, value);
    }

    public static readonly DependencyProperty LabelProperty = DependencyProperty.Register(
        nameof(Label), typeof(string), typeof(MeridianIconTile),
        new PropertyMetadata(null));

    public string? Label
    {
        get => (string?)GetValue(LabelProperty);
        set => SetValue(LabelProperty, value);
    }

    public static readonly DependencyProperty TileSizeProperty = DependencyProperty.Register(
        nameof(TileSize), typeof(double), typeof(MeridianIconTile),
        new PropertyMetadata(48d));

    /// <summary>Preferred width/height of the tile. Defaults to 48, above the 44px tap-target
    /// floor the default style enforces separately via MinWidth/MinHeight.</summary>
    public double TileSize
    {
        get => (double)GetValue(TileSizeProperty);
        set => SetValue(TileSizeProperty, value);
    }
}
