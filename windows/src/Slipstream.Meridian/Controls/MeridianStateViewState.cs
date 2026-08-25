namespace Slipstream.Meridian.Controls;

/// <summary>
/// The four-state contract shared with the Compose implementation's state view: exactly one
/// of Loading, Content, Empty, or Error is showing at any time.
/// </summary>
public enum MeridianStateViewState
{
    Loading,
    Content,
    Empty,
    Error,
}
