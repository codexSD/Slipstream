package com.slipstream.core.pairing

import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairingCode
import kotlinx.serialization.Serializable

/** pairing.md §3: the state both devices' [PairingSession] step through, in lockstep. */
enum class PairingState {
    Idle,
    AwaitingConfirmation,
    Confirmed,
    Cancelled,
}

/** Wire shape of a `pair.offer` payload (pairing.md §3 example JSON). */
@Serializable
data class PairingOffer(
    val version: Int = 1,
    val deviceId: String,
    val name: String,
    val fingerprint: String,
)

/** What a successfully-confirmed pairing session produced: the remote device's identity as
 * it was verified during the exchange. The caller combines this with the X.509 certificate
 * obtained from the TLS handshake itself (never from the wire) to build a [com.slipstream.core.identity.PairedPeer]. */
data class PairingOutcome(
    val deviceId: String,
    val name: String,
    val fingerprint: String,
)

/**
 * pairing.md §3-§5: one side's half of the pairing state machine. Both devices run an
 * identical [PairingSession]; [PairingCoordinator] drives it over the wire.
 *
 * The critical invariant lives in [receiveOffer]: the code is derived from
 * [verifiedFingerprint] — the certificate fingerprint proven by the TLS handshake — never
 * from [PairingOffer.fingerprint], which is peer-supplied text and proves nothing by
 * itself. An offer whose claimed fingerprint disagrees with the verified one is rejected
 * (state -> [PairingState.Cancelled]) before a code is ever computed.
 *
 * Persistence (by the caller, via [PairingOutcome]) only ever happens once both
 * [confirmLocally] and [receiveConfirm] have been observed — a single-sided confirmation
 * leaves the session in [PairingState.AwaitingConfirmation] forever.
 */
class PairingSession(private val local: DeviceIdentity) {

    var state: PairingState = PairingState.Idle
        private set

    var code: String? = null
        private set

    var result: PairingOutcome? = null
        private set

    private var remoteOffer: PairingOffer? = null
    private var remoteFingerprint: String? = null
    private var localConfirmed = false
    private var remoteConfirmed = false

    /** This device's own offer, ready to send as the `pair.offer` payload. */
    fun localOffer(): PairingOffer = PairingOffer(
        deviceId = local.deviceId,
        name = local.displayName,
        fingerprint = local.fingerprint,
    )

    /**
     * Handles an incoming `pair.offer`. [verifiedFingerprint] must be the fingerprint of the
     * certificate actually presented during the TLS handshake — not anything read off the
     * wire. Rejects (cancels) if [offer]'s claimed fingerprint doesn't match it, before
     * deriving any code.
     */
    fun receiveOffer(offer: PairingOffer, verifiedFingerprint: String) {
        if (state == PairingState.Cancelled || state == PairingState.Confirmed) return

        if (offer.fingerprint != verifiedFingerprint) {
            state = PairingState.Cancelled
            code = null
            return
        }

        remoteOffer = offer
        remoteFingerprint = verifiedFingerprint
        code = PairingCode.derive(local.fingerprint, verifiedFingerprint)
        state = PairingState.AwaitingConfirmation
    }

    /** This device's user accepted the displayed code. */
    fun confirmLocally() {
        if (state != PairingState.AwaitingConfirmation) return
        localConfirmed = true
        tryFinish()
    }

    /** The remote device sent `pair.confirm`. */
    fun receiveConfirm() {
        if (state != PairingState.AwaitingConfirmation) return
        remoteConfirmed = true
        tryFinish()
    }

    /** Either side declined, or the remote sent `pair.cancel`, or the connection dropped. */
    fun cancel() {
        if (state == PairingState.Confirmed) return
        state = PairingState.Cancelled
        code = null
        result = null
    }

    private fun tryFinish() {
        // Persistence only ever happens with BOTH confirmations observed - a single-sided
        // confirm must never pair.
        if (!localConfirmed || !remoteConfirmed) return
        val offer = remoteOffer ?: return
        val fingerprint = remoteFingerprint ?: return
        result = PairingOutcome(deviceId = offer.deviceId, name = offer.name, fingerprint = fingerprint)
        state = PairingState.Confirmed
    }
}
