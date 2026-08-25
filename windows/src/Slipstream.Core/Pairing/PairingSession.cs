using Slipstream.Core.Control;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Pairing;

public enum PairingState
{
    AwaitingOffer,
    AwaitingConfirmation,
    Paired,
    Cancelled,
}

/// <summary>
/// One pairing attempt, on one device. Spec §4.
///
/// The six-digit code is derived from both certificate fingerprints and is never
/// transmitted — each side computes it and the user compares them by eye. Pairing
/// completes only on mutual confirmation: this device's user AND the remote.
/// </summary>
public sealed class PairingSession(DeviceIdentity localIdentity)
{
    private readonly Lock _gate = new();

    private PairOfferPayload? _offer;
    private string? _verifiedFingerprint;
    private bool _confirmedLocally;
    private bool _confirmedRemotely;

    public PairingState State { get; private set; } = PairingState.AwaitingOffer;

    /// <summary>The six digits to show the user. Null until an offer arrives.</summary>
    public string? Code { get; private set; }

    public PairedPeer? Result { get; private set; }

    /// <param name="verifiedFingerprint">
    /// Taken from the TLS certificate, never from <paramref name="offer"/>. The offer is
    /// peer-supplied text; the certificate is the only thing the handshake proves.
    /// </param>
    public void ReceiveOffer(PairOfferPayload offer, string verifiedFingerprint)
    {
        lock (_gate)
        {
            if (State != PairingState.AwaitingOffer) return;

            // A peer claiming a fingerprint it does not hold is either broken or hostile.
            if (!string.Equals(offer.Fingerprint, verifiedFingerprint, StringComparison.OrdinalIgnoreCase))
            {
                State = PairingState.Cancelled;
                return;
            }

            // Never pair with ourselves.
            if (string.Equals(verifiedFingerprint, localIdentity.Fingerprint, StringComparison.OrdinalIgnoreCase))
            {
                State = PairingState.Cancelled;
                return;
            }

            _offer = offer;
            _verifiedFingerprint = verifiedFingerprint;
            Code = PairingCode.Derive(localIdentity.Fingerprint, verifiedFingerprint);
            State = PairingState.AwaitingConfirmation;
        }
    }

    /// <summary>This device's user confirmed the codes match.</summary>
    public void ConfirmLocally()
    {
        lock (_gate)
        {
            if (State != PairingState.AwaitingConfirmation) return;

            _confirmedLocally = true;
            CompleteIfMutual();
        }
    }

    public void ReceiveRemoteConfirm()
    {
        lock (_gate)
        {
            if (State != PairingState.AwaitingConfirmation) return;

            _confirmedRemotely = true;
            CompleteIfMutual();
        }
    }

    public void Cancel()
    {
        lock (_gate)
        {
            if (State == PairingState.Paired) return;
            State = PairingState.Cancelled;
        }
    }

    private void CompleteIfMutual()
    {
        // A single-sided confirmation never pairs. Both users looked at the code.
        if (!_confirmedLocally || !_confirmedRemotely) return;

        Result = new PairedPeer(
            _offer!.DeviceId,
            _verifiedFingerprint!,
            _offer.Name,
            DateTimeOffset.UtcNow);

        State = PairingState.Paired;
    }
}
