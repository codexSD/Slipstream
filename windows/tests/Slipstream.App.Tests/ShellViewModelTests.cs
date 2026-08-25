using Slipstream.App.Services;
using Slipstream.App.Shell;
using Slipstream.App.Tests.Fakes;
using Slipstream.Meridian.Controls;

namespace Slipstream.App.Tests;

public class ShellViewModelTests
{
    [Fact]
    public void Exposes_the_five_destinations_in_order()
    {
        var vm = new ShellViewModel(new FakePeerHost());
        Assert.Equal(["Device", "Browse phone", "Transfers", "History", "Settings"],
                     vm.Destinations.Select(d => d.Label));
    }

    [Fact]
    public void Starts_on_the_device_page() => Assert.Equal("Device", new ShellViewModel(new FakePeerHost()).Selected.Label);

    [Fact]
    public void Connection_status_follows_the_host()
    {
        var host = new FakePeerHost();
        var vm = new ShellViewModel(host);

        host.RaiseState(PeerConnectionState.Connected, "Pixel 9");
        Assert.Equal(MeridianStatus.Positive, vm.ConnectionStatus);
        Assert.Equal("Pixel 9", vm.ConnectionLabel);

        host.RaiseState(PeerConnectionState.Searching, null);
        Assert.Equal(MeridianStatus.Info, vm.ConnectionStatus);
        Assert.Equal("Searching…", vm.ConnectionLabel);

        host.RaiseState(PeerConnectionState.Lost, null);
        Assert.Equal(MeridianStatus.Critical, vm.ConnectionStatus);
    }

    [Fact]
    public void Degraded_link_reads_as_a_warning_naming_the_band()
    {
        var host = new FakePeerHost();
        var vm = new ShellViewModel(host);

        host.RaiseState(PeerConnectionState.Degraded, "Pixel 9", band: "2.4 GHz");

        Assert.Equal(MeridianStatus.Warning, vm.ConnectionStatus);
        // Spec §16: explain the slow link rather than leaving the user to wonder.
        Assert.Equal("2.4 GHz — slower link", vm.ConnectionLabel);
    }

    [Fact]
    public async Task Connection_status_updates_safely_when_StateChanged_fires_from_a_background_thread()
    {
        // Task 16: PeerHost's NetworkChanged-driven reconnect loop raises StateChanged from
        // its own background Task far more often than the old one-shot connect path did.
        // OnPeerStateChanged must marshal through RunOnUiThread before touching
        // ConnectionStatus/ConnectionLabel, rather than mutating those observable
        // properties straight off that thread.
        var host = new FakePeerHost();
        var vm = new ShellViewModel(host);

        await Task.Run(() => host.RaiseState(PeerConnectionState.Lost, null));

        var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(5);
        while (vm.ConnectionStatus != MeridianStatus.Critical && DateTime.UtcNow < deadline)
            await Task.Delay(10);

        Assert.Equal(MeridianStatus.Critical, vm.ConnectionStatus);
        Assert.Equal("Connection lost", vm.ConnectionLabel);
    }
}
