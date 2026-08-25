using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingWindowTests
{
    private sealed class FakeTime(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan by) => _now += by;
    }

    private static FakeTime At(string instant) => new(DateTimeOffset.Parse(instant));

    [Fact]
    public void Is_closed_by_default()
    {
        // The safe default is the whole point: pairing is never implicitly available.
        Assert.False(new PairingWindow().IsOpen);
    }

    [Fact]
    public void Opens_on_an_explicit_call()
    {
        var window = new PairingWindow();
        window.Open();

        Assert.True(window.IsOpen);
        Assert.NotNull(window.ClosesAt);
    }

    [Fact]
    public void Closes_automatically_after_120_seconds()
    {
        var time = At("2026-08-25T10:00:00Z");
        var window = new PairingWindow(time);
        window.Open();

        time.Advance(TimeSpan.FromSeconds(119));
        Assert.True(window.IsOpen);

        time.Advance(TimeSpan.FromSeconds(2));
        Assert.False(window.IsOpen);
    }

    [Fact]
    public void Duration_is_120_seconds()
    {
        Assert.Equal(TimeSpan.FromSeconds(120), PairingWindow.Duration);
    }

    [Fact]
    public void Close_shuts_it_immediately()
    {
        var window = new PairingWindow();
        window.Open();
        window.Close();

        Assert.False(window.IsOpen);
        Assert.Null(window.ClosesAt);
    }

    [Fact]
    public void Reopening_extends_the_deadline()
    {
        var time = At("2026-08-25T10:00:00Z");
        var window = new PairingWindow(time);

        window.Open();
        time.Advance(TimeSpan.FromSeconds(100));
        window.Open();
        time.Advance(TimeSpan.FromSeconds(100));

        Assert.True(window.IsOpen);
    }

    [Fact]
    public void Raises_Closed_when_closed_explicitly()
    {
        var window = new PairingWindow();
        var raised = 0;
        window.Closed += () => raised++;

        window.Open();
        window.Close();

        Assert.Equal(1, raised);
    }

    [Fact]
    public void Closing_an_already_closed_window_does_not_raise_again()
    {
        var window = new PairingWindow();
        var raised = 0;
        window.Closed += () => raised++;

        window.Close();
        window.Close();

        Assert.Equal(0, raised);
    }

    [Fact]
    public void ClosesAt_is_null_once_expired()
    {
        var time = At("2026-08-25T10:00:00Z");
        var window = new PairingWindow(time);
        window.Open();

        time.Advance(TimeSpan.FromSeconds(200));

        Assert.Null(window.ClosesAt);
    }
}
