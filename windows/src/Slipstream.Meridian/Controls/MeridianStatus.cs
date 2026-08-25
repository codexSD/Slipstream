namespace Slipstream.Meridian.Controls;

/// <summary>
/// The five signal states shared across every Meridian status affordance (pills, badges,
/// transfer-state chrome). Mirrors the Compose implementation's five members one-for-one
/// so the two platforms stay recognisably one system.
/// </summary>
public enum MeridianStatus
{
    Positive,
    Warning,
    Critical,
    Info,
    Neutral,
}
