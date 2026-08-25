using Slipstream.App.Pages;
using Slipstream.App.Services;
using Slipstream.App.Tests.Fakes;
using Xunit;

namespace Slipstream.App.Tests;

/// <summary>
/// Tests the plain C# <see cref="DeviceViewModel"/> directly — no UI thread needed, since
/// WinUI controls can't be instantiated headless under bare `dotnet test` (confirmed by
/// Tasks 5/7/8's COMException findings).
/// </summary>
public class DeviceViewModelTests
{
    [Fact]
    public void Peer_state_stat_reflects_connection_state()
    {
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        host.RaiseState(PeerConnectionState.Connected, "Kai's iPhone");

        Assert.Equal("Connected", vm.PeerStateText);
    }

    [Fact]
    public void Peer_state_stat_shows_not_connected_when_idle()
    {
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        Assert.Equal("Not connected", vm.PeerStateText);
    }

    [Fact]
    public void Link_rate_and_transferred_today_are_resting_placeholders_absent_a_live_source()
    {
        // Core does not currently expose a live link-rate or per-day-transferred-bytes
        // stream to IPeerHost — that data belongs to Task 12's TransferQueue (live rate,
        // via TransferProgress) and a future history/stats aggregation. Until that wiring
        // exists, DeviceViewModel shows a clearly resting value rather than fabricating one.
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        host.RaiseState(PeerConnectionState.Connected, "Kai's iPhone");

        Assert.Equal("—", vm.LinkRateText);
        Assert.Equal("—", vm.TransferredTodayText);
    }

    [Fact]
    public void Hero_metric_shows_resting_value_when_no_live_transfer_is_in_progress()
    {
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        host.RaiseState(PeerConnectionState.Connected, "Kai's iPhone");

        Assert.Equal("—", vm.HeroRateText);
    }

    [Fact]
    public void Discovery_detail_lists_all_four_strategies()
    {
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        Assert.Equal(4, vm.DiscoveryStrategies.Count);
        Assert.Contains(vm.DiscoveryStrategies, s => s.Code == "S1" && s.Name == "Cached endpoint");
        Assert.Contains(vm.DiscoveryStrategies, s => s.Code == "S2" && s.Name == "Gateway probe");
        Assert.Contains(vm.DiscoveryStrategies, s => s.Code == "S3" && s.Name == "Multicast");
        Assert.Contains(vm.DiscoveryStrategies, s => s.Code == "S4" && s.Name == "Subnet sweep");
    }

    [Fact]
    public void Discovery_detail_names_the_winner_and_elapsed_time_once_known()
    {
        var host = new FakePeerHost();
        host.RaiseDiscovery("gateway-probe", TimeSpan.FromMilliseconds(1230));
        var vm = new DeviceViewModel(host);

        host.RaiseState(PeerConnectionState.Connected, "Kai's iPhone");

        var winner = Assert.Single(vm.DiscoveryStrategies, s => s.IsWinner);
        Assert.Equal("S2", winner.Code);
        Assert.Equal("Gateway probe", winner.Name);
        Assert.Equal("Connected via gateway probe in 1.2s", vm.DiscoverySummaryText);
    }

    [Fact]
    public void Discovery_detail_has_no_winner_before_any_discovery_completes()
    {
        var host = new FakePeerHost();
        var vm = new DeviceViewModel(host);

        Assert.DoesNotContain(vm.DiscoveryStrategies, s => s.IsWinner);
        Assert.Equal("Not yet connected.", vm.DiscoverySummaryText);
    }
}
