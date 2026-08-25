using Slipstream.App.Pages;
using Slipstream.App.Tests.Fakes;

namespace Slipstream.App.Tests;

public class PairingViewModelTests
{
    [Fact]
    public void Shows_the_code_and_asks_the_user_to_compare_it()
    {
        var vm = new PairingViewModel(new FakePeerHost());
        vm.OnCodeReceived("482915", peerName: "Pixel 9");

        Assert.Equal("482915", vm.Code);
        // Naming the other device and the action is what makes the comparison happen.
        Assert.Equal("Does Pixel 9 show this same code?", vm.Prompt);
        Assert.True(vm.CanConfirm);
    }

    [Fact]
    public void Confirm_is_unavailable_until_a_code_arrives()
        => Assert.False(new PairingViewModel(new FakePeerHost()).CanConfirm);

    [Fact]
    public void Declining_reports_failure_without_pairing()
    {
        var host = new FakePeerHost();
        var vm = new PairingViewModel(host);
        vm.OnCodeReceived("482915", "Pixel 9");

        vm.DeclineCommand.Execute(null);

        Assert.False(host.Paired);
        Assert.Equal("Pairing cancelled.", vm.Status);
    }

    [Fact]
    public void Counts_down_the_120_second_window()
    {
        var vm = new PairingViewModel(new FakePeerHost());
        vm.OnWindowOpened(DateTimeOffset.UnixEpoch.AddSeconds(120), now: DateTimeOffset.UnixEpoch);
        Assert.Equal("2:00", vm.TimeRemaining);
    }
}
