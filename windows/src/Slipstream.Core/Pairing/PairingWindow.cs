namespace Slipstream.Core.Pairing;

/// <summary>
/// The gate that makes pairing safe: an explicit, user-opened, time-boxed window.
///
/// Outside this window the accept path behaves exactly as it did before pairing
/// existed — unpaired connections are dropped before a message is read. The window
/// is the *only* thing that changes that, and it closes by itself.
/// </summary>
public sealed class PairingWindow(TimeProvider? time = null)
{
    public static readonly TimeSpan Duration = TimeSpan.FromSeconds(120);

    private readonly TimeProvider _time = time ?? TimeProvider.System;
    private readonly Lock _gate = new();

    private DateTimeOffset? _closesAt;

    /// <summary>Raised when the window closes explicitly. Expiry is silent — poll <see cref="IsOpen"/>.</summary>
    public event Action? Closed;

    public bool IsOpen
    {
        get
        {
            lock (_gate)
            {
                return _closesAt is { } deadline && _time.GetUtcNow() < deadline;
            }
        }
    }

    public DateTimeOffset? ClosesAt
    {
        get
        {
            lock (_gate)
            {
                if (_closesAt is { } deadline && _time.GetUtcNow() < deadline) return deadline;
                return null;
            }
        }
    }

    public void Open()
    {
        lock (_gate)
        {
            _closesAt = _time.GetUtcNow() + Duration;
        }
    }

    public void Close()
    {
        bool wasOpen;
        lock (_gate)
        {
            wasOpen = _closesAt is { } deadline && _time.GetUtcNow() < deadline;
            _closesAt = null;
        }

        if (wasOpen) Closed?.Invoke();
    }
}
