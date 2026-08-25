using System.Windows.Input;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Slipstream.App.Services;
using Slipstream.Core.Identity;

namespace Slipstream.App.Pages;

/// <summary>
/// Drives the pairing dialog (spec §4, <c>protocol/pairing.md</c>). Both devices derive the
/// same 6-digit code from their certificate fingerprints; the code must be shown prominently
/// and the user told exactly what to compare, because a user who confirms without comparing
/// is the one thing the protocol cannot defend against (<c>pairing.md</c> §5).
/// </summary>
/// <remarks>
/// The real integration is <see cref="StartAsync"/>: it calls
/// <see cref="IPeerHost.PairAsync"/> with <see cref="OnConfirmRequested"/> as the confirm
/// callback. <c>IPeerHost</c> invokes that callback with the derived code once both sides
/// have exchanged <c>pair.offer</c>; the callback surfaces the code via
/// <see cref="OnCodeReceived"/> and then awaits a <see cref="TaskCompletionSource{Boolean}"/>
/// that <see cref="ConfirmCommand"/> or <see cref="DeclineCommand"/> completes when the user
/// answers — that boolean is exactly the value <c>PairAsync</c> uses to decide whether to
/// send <c>pair.confirm</c> or <c>pair.cancel</c> on the wire.
/// </remarks>
public sealed partial class PairingViewModel : ObservableObject
{
    private readonly IPeerHost _peerHost;
    private TaskCompletionSource<bool>? _pendingConfirmation;
    private DateTimeOffset _windowEnd;

    private string _code = string.Empty;
    private string _prompt = string.Empty;
    private bool _canConfirm;
    private string _status = string.Empty;
    private string _timeRemaining = "0:00";

    /// <summary>The 6-digit code both devices derived and must show identically.</summary>
    public string Code
    {
        get => _code;
        private set => SetProperty(ref _code, value);
    }

    /// <summary>e.g. "Does Pixel 9 show this same code?" — names the other device and the
    /// action, so comparing the codes is unmissable rather than optional.</summary>
    public string Prompt
    {
        get => _prompt;
        private set => SetProperty(ref _prompt, value);
    }

    /// <summary>False until a code has arrived — there is nothing to confirm before then.</summary>
    public bool CanConfirm
    {
        get => _canConfirm;
        private set
        {
            if (SetProperty(ref _canConfirm, value))
                (ConfirmCommand as RelayCommand)?.NotifyCanExecuteChanged();
        }
    }

    /// <summary>User-facing outcome of the exchange, e.g. "Pairing cancelled.".</summary>
    public string Status
    {
        get => _status;
        private set => SetProperty(ref _status, value);
    }

    /// <summary>The 120-second pairing window, formatted "M:SS".</summary>
    public string TimeRemaining
    {
        get => _timeRemaining;
        private set => SetProperty(ref _timeRemaining, value);
    }

    public ICommand ConfirmCommand { get; }
    public ICommand DeclineCommand { get; }

    public PairingViewModel(IPeerHost peerHost)
    {
        ArgumentNullException.ThrowIfNull(peerHost);
        _peerHost = peerHost;

        ConfirmCommand = new RelayCommand(Confirm, () => CanConfirm);
        DeclineCommand = new RelayCommand(Decline);
    }

    /// <summary>
    /// Starts (or joins) a pairing attempt: calls <see cref="IPeerHost.PairAsync"/> with
    /// <see cref="OnConfirmRequested"/> wired as the confirm callback, so the code the peer
    /// host derives flows straight into <see cref="OnCodeReceived"/> and the user's answer
    /// flows straight back out.
    /// </summary>
    public Task<PairedPeer?> StartAsync(CancellationToken ct) => _peerHost.PairAsync(OnConfirmRequested, ct);

    /// <summary>The confirm callback handed to <see cref="IPeerHost.PairAsync"/>. Surfaces the
    /// code, then waits for <see cref="ConfirmCommand"/> or <see cref="DeclineCommand"/>.</summary>
    private Task<bool> OnConfirmRequested(string code, CancellationToken ct)
    {
        OnCodeReceived(code, _peerHost.PeerName ?? "the other device");

        var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pendingConfirmation = tcs;
        ct.Register(() => tcs.TrySetCanceled(ct));
        return tcs.Task;
    }

    /// <summary>Called once the derived code is known — from <see cref="OnConfirmRequested"/>
    /// in real usage, or directly by tests.</summary>
    public void OnCodeReceived(string code, string peerName)
    {
        Code = code;
        Prompt = $"Does {peerName} show this same code?";
        Status = string.Empty;
        CanConfirm = true;
    }

    /// <summary>Opens the 120-second pairing window and shows the initial countdown.</summary>
    public void OnWindowOpened(DateTimeOffset windowEnd, DateTimeOffset now)
    {
        _windowEnd = windowEnd;
        UpdateTimeRemaining(now);
    }

    /// <summary>Recomputes <see cref="TimeRemaining"/> against <paramref name="now"/>. Callers
    /// driving a real countdown (e.g. a DispatcherTimer tick) call this once a second.</summary>
    public void Tick(DateTimeOffset now) => UpdateTimeRemaining(now);

    private void UpdateTimeRemaining(DateTimeOffset now)
    {
        var remaining = _windowEnd - now;
        if (remaining < TimeSpan.Zero)
            remaining = TimeSpan.Zero;

        TimeRemaining = $"{(int)remaining.TotalMinutes}:{remaining.Seconds:D2}";
    }

    private void Confirm()
    {
        if (!CanConfirm)
            return;

        CanConfirm = false;
        Status = "Pairing confirmed.";
        _pendingConfirmation?.TrySetResult(true);
    }

    private void Decline()
    {
        CanConfirm = false;
        Status = "Pairing cancelled.";
        _pendingConfirmation?.TrySetResult(false);
    }
}
