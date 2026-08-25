using Slipstream.Core.Control;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Pairing;

public sealed record PairingProgress(PairingState State, string? Code, string? PeerName);

/// <summary>
/// Drives one pairing attempt to completion over an already-established, unpinned
/// connection. Symmetric: both devices run this, differing only in who sends the
/// first offer.
///
/// The wire flow is deliberately tiny — offer, offer, confirm, confirm — because
/// this is the one code path an unpaired stranger can reach.
/// </summary>
#pragma warning disable CS9113 // client is part of the public constructor shape (matches ControlClient-based
// construction used elsewhere) but PairAsync operates on an already-established connection.
public sealed class PairingCoordinator(
    DeviceIdentity identity,
    PairedPeerStore peers,
    ControlClient client,
    PairingWindow window)
#pragma warning restore CS9113
{
    public async Task<PairedPeer?> PairAsync(
        ControlConnection connection,
        bool isInitiator,
        Func<string, CancellationToken, Task<bool>> confirmCode,
        IProgress<PairingProgress>? progress,
        CancellationToken cancellationToken)
    {
        var session = new PairingSession(identity);

        var offer = new PairOfferPayload(
            SlipstreamPorts.ProtocolVersion, identity.DeviceId, identity.DisplayName, identity.Fingerprint);

        try
        {
            if (isInitiator)
                await connection.SendAsync(ControlMessage.Request("pair.offer", "1", offer), cancellationToken);

            while (!cancellationToken.IsCancellationRequested)
            {
                var message = await connection.ReceiveAsync(cancellationToken);
                if (message is null) break; // peer closed

                switch (message.Type)
                {
                    case "pair.offer":
                    {
                        var payload = message.PayloadAs<PairOfferPayload>();
                        if (payload is null) return Fail(session, progress);

                        // The certificate fingerprint, never the payload's claim.
                        session.ReceiveOffer(payload, connection.PeerFingerprint);

                        if (session.State == PairingState.Cancelled) return Fail(session, progress);

                        if (!isInitiator)
                            await connection.SendAsync(ControlMessage.Request("pair.offer", "1", offer), cancellationToken);

                        progress?.Report(new PairingProgress(session.State, session.Code, payload.Name));

                        // Ask the user. This blocks the flow, deliberately — the whole
                        // security argument rests on a human comparing two numbers.
                        var accepted = await confirmCode(session.Code!, cancellationToken);

                        if (!accepted)
                        {
                            await connection.SendAsync(ControlMessage.Event("pair.cancel"), cancellationToken);
                            return Fail(session, progress);
                        }

                        session.ConfirmLocally();
                        await connection.SendAsync(ControlMessage.Event("pair.confirm"), cancellationToken);
                        break;
                    }

                    case "pair.confirm":
                        session.ReceiveRemoteConfirm();
                        break;

                    case "pair.cancel":
                        return Fail(session, progress);

                    default:
                        // Restricted path: nothing else is answerable here.
                        continue;
                }

                if (session.State == PairingState.Paired)
                {
                    peers.Pair(session.Result!);
                    window.Close();

                    progress?.Report(new PairingProgress(PairingState.Paired, session.Code, session.Result!.DisplayName));
                    return session.Result;
                }
            }
        }
        catch (Exception) when (!cancellationToken.IsCancellationRequested)
        {
            // The peer tore the connection down without a clean TLS close-notify —
            // routine after a decline, a timeout, or a crash on the other end. Treat
            // exactly like a graceful close: this attempt failed, nothing more to do.
        }

        // The peer hung up. If we confirmed and they never did, that is a decline.
        return Fail(session, progress);
    }

    private static PairedPeer? Fail(PairingSession session, IProgress<PairingProgress>? progress)
    {
        session.Cancel();
        progress?.Report(new PairingProgress(PairingState.Cancelled, session.Code, null));
        return null;
    }
}
