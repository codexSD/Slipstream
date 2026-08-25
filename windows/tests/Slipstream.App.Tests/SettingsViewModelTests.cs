using Slipstream.App.Pages;
using Slipstream.App.Services;
using Slipstream.App.Tests.Fakes;

namespace Slipstream.App.Tests;

/// <summary>
/// Task 14: SettingsViewModel wraps SettingsStore with the clamping/validation the brief
/// requires — stream count in [1,8], a download folder that falls back if it no longer
/// exists on disk, theme/autostart preferences that round-trip, and a testable "Pair a
/// device" seam (an injected launcher delegate rather than a live PairingDialog/UI thread).
/// </summary>
public class SettingsViewModelTests : IDisposable
{
    private readonly string _dir;
    private readonly string _path;

    public SettingsViewModelTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "slipstream-settingsvm-tests-" + Guid.NewGuid());
        Directory.CreateDirectory(_dir);
        _path = Path.Combine(_dir, "settings.json");
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { /* best effort */ }
    }

    private SettingsViewModel MakeViewModel(Func<CancellationToken, Task>? pairLauncher = null) =>
        new(new SettingsStore(_path), new FakePeerHost(), pairLauncher);

    [Theory]
    [InlineData(0, 1)]
    [InlineData(1, 1)]
    [InlineData(8, 8)]
    [InlineData(9, 8)]
    [InlineData(-5, 1)]
    [InlineData(5, 5)]
    public void StreamCount_clamps_to_1_through_8(int input, int expected)
    {
        var vm = MakeViewModel();
        vm.StreamCount = input;
        Assert.Equal(expected, vm.StreamCount);
    }

    [Fact]
    public void StreamCount_persists_across_instances()
    {
        var vm1 = MakeViewModel();
        vm1.StreamCount = 7;

        var vm2 = MakeViewModel();
        Assert.Equal(7, vm2.StreamCount);
    }

    [Fact]
    public void DownloadDirectory_persists_when_it_exists_on_disk()
    {
        var vm = MakeViewModel();
        vm.DownloadDirectory = _dir;

        Assert.Equal(_dir, vm.DownloadDirectory);

        var vm2 = MakeViewModel();
        Assert.Equal(_dir, vm2.DownloadDirectory);
    }

    [Fact]
    public void DownloadDirectory_falls_back_when_the_path_does_not_exist()
    {
        var vm = MakeViewModel();
        var missing = Path.Combine(_dir, "does-not-exist-" + Guid.NewGuid());

        vm.DownloadDirectory = missing;

        Assert.NotEqual(missing, vm.DownloadDirectory);
        Assert.True(Directory.Exists(vm.DownloadDirectory) || vm.DownloadDirectory == SettingsStore.DefaultDownloadDirectory());
    }

    [Theory]
    [InlineData(AppTheme.System)]
    [InlineData(AppTheme.Light)]
    [InlineData(AppTheme.Dark)]
    public void Theme_persists_across_instances(AppTheme theme)
    {
        var vm1 = MakeViewModel();
        vm1.Theme = theme;

        var vm2 = MakeViewModel();
        Assert.Equal(theme, vm2.Theme);
    }

    [Fact]
    public void Autostart_round_trips()
    {
        var vm1 = MakeViewModel();
        Assert.False(vm1.AutostartEnabled);

        vm1.AutostartEnabled = true;

        var vm2 = MakeViewModel();
        Assert.True(vm2.AutostartEnabled);

        vm2.AutostartEnabled = false;

        var vm3 = MakeViewModel();
        Assert.False(vm3.AutostartEnabled);
    }

    [Fact]
    public async Task PairDeviceCommand_invokes_the_injected_launcher()
    {
        var invoked = false;
        var vm = MakeViewModel(ct =>
        {
            invoked = true;
            return Task.CompletedTask;
        });

        await vm.PairDeviceCommand.ExecuteAsync(null);

        Assert.True(invoked);
    }

    [Fact]
    public async Task PairDeviceCommand_is_a_no_op_without_a_launcher()
    {
        var vm = MakeViewModel();

        // Should not throw even though no launcher was supplied.
        await vm.PairDeviceCommand.ExecuteAsync(null);
    }

    [Fact]
    public void Band_reflects_the_peer_hosts_reported_band()
    {
        var host = new FakePeerHost();
        var vm = new SettingsViewModel(new SettingsStore(_path), host, null);

        host.RaiseState(PeerConnectionState.Connected, "Pixel 9", band: "5 GHz");

        Assert.Equal("5 GHz", vm.BandText);
    }

    [Fact]
    public void Band_falls_back_when_unknown()
    {
        var vm = MakeViewModel();
        Assert.Equal("Unknown", vm.BandText);
    }
}
