using Slipstream.Meridian.Controls;

namespace Slipstream.App.Tests.Meridian;

public class StatusPillTests
{
    [Theory]
    [InlineData(MeridianStatus.Positive, "MeridianPositiveBrush")]
    [InlineData(MeridianStatus.Warning,  "MeridianWarningBrush")]
    [InlineData(MeridianStatus.Critical, "MeridianCriticalBrush")]
    [InlineData(MeridianStatus.Info,     "MeridianInfoBrush")]
    [InlineData(MeridianStatus.Neutral,  "MeridianInkMutedBrush")]
    public void Maps_each_status_to_its_signal_brush(MeridianStatus status, string expectedKey)
        => Assert.Equal(expectedKey, MeridianStatusPill.BrushKeyFor(status));

    [Fact]
    public void Label_is_required_so_colour_is_never_the_only_cue()
    {
        // The API makes the non-colour cue impossible to omit, rather than leaving it
        // to reviewer discipline.
        Assert.Throws<ArgumentException>(() => MeridianStatusPill.Validate(status: MeridianStatus.Critical, label: ""));
    }
}
