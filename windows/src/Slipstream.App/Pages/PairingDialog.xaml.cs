using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Slipstream.App.Pages;
using Slipstream.App.Services;

namespace Slipstream_App.Pages;

/// <summary>
/// The pairing flow dialog (spec §4, <c>protocol/pairing.md</c>): shows the 6-digit code at
/// hero-metric size, names the peer, and puts a 120-second countdown next to Confirm/Decline.
/// </summary>
/// <remarks>
/// <para>
/// Launch point: Settings (Task 14) is where the spec puts the "Pair a device" entry point
/// that opens this dialog. Settings does not exist yet in this codebase, so this dialog is
/// self-contained and ready to launch from anywhere — Task 14 need only construct one and
/// call <see cref="ShowAndPairAsync"/> from its "Pair a device" button handler. There is no
/// interim button wired up here deliberately: adding one to a page that Task 14 owns and will
/// rebuild would just create churn.
/// </para>
/// <para>
/// The 120-second countdown ticks via a plain <see cref="DispatcherTimer"/> tied to this
/// dialog's lifetime (started in <see cref="ShowAndPairAsync"/>, stopped when the dialog
/// closes) rather than living inside <see cref="PairingViewModel"/> itself, so the view model
/// stays dispatcher-free and unit-testable off the UI thread.
/// </para>
/// </remarks>
public sealed partial class PairingDialog : ContentDialog
{
    private readonly DispatcherTimer _countdown = new() { Interval = TimeSpan.FromSeconds(1) };

    public PairingViewModel ViewModel { get; }

    public PairingDialog(IPeerHost peerHost)
    {
        ViewModel = new PairingViewModel(peerHost);
        InitializeComponent();

        _countdown.Tick += (_, _) => ViewModel.Tick(DateTimeOffset.UtcNow);

        PrimaryButtonClick += (_, _) => ViewModel.ConfirmCommand.Execute(null);
        CloseButtonClick += (_, _) => ViewModel.DeclineCommand.Execute(null);
        Closed += (_, _) => _countdown.Stop();
    }

    /// <summary>
    /// Opens the 120-second pairing window, shows the dialog, and drives
    /// <see cref="PairingViewModel.StartAsync"/> in the background so the derived code and
    /// the user's Confirm/Decline answer flow straight through to <c>IPeerHost.PairAsync</c>.
    /// </summary>
    public async Task ShowAndPairAsync(CancellationToken ct)
    {
        var now = DateTimeOffset.UtcNow;
        ViewModel.OnWindowOpened(now.AddSeconds(120), now);
        _countdown.Start();

        var pairing = ViewModel.StartAsync(ct);
        await ShowAsync();
        await pairing;
    }
}
