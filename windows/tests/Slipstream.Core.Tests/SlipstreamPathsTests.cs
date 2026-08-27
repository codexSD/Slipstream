namespace Slipstream.Core.Tests;

/// <summary>
/// The PC's TLS identity moved depending on how the app was launched, so the phone — which
/// pins the fingerprint it paired with — stopped recognising it after a restart. Two complete
/// state directories existed on the real machine, each with its own identity.pfx: one under
/// the plain LOCALAPPDATA, one redirected into an MSIX container. Whatever else changes, the
/// location this resolves to must not depend on the launch context.
/// </summary>
public class SlipstreamPathsTests
{
    [Fact]
    public void State_lives_under_the_user_profile_not_a_redirectable_app_data_folder()
    {
        var profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

        Assert.StartsWith(profile, SlipstreamPaths.StateDirectory);

        // LOCALAPPDATA is precisely the folder that gets redirected per package container.
        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        Assert.DoesNotContain(
            Path.Combine(localAppData, "Slipstream"),
            SlipstreamPaths.StateDirectory);
    }

    [Fact]
    public void Everything_that_must_survive_a_restart_shares_one_directory()
    {
        Assert.Equal(SlipstreamPaths.StateDirectory, Path.GetDirectoryName(SlipstreamPaths.LogFile));
    }
}
