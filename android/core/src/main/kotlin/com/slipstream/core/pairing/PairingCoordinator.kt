package com.slipstream.core.pairing

import com.slipstream.core.control.ControlConnection
import com.slipstream.core.control.ControlMessage
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeer
import com.slipstream.core.identity.PairedPeerStore
import java.security.cert.X509Certificate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** The only three message types a restricted (unpaired, in-window) connection may exchange
 * (pairing.md §2). Any other [ControlMessage.type] must be ignored, not dispatched. */
object PairingMessageTypes {
    const val OFFER = "pair.offer"
    const val CONFIRM = "pair.confirm"
    const val CANCEL = "pair.cancel"
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Drives one [PairingSession] to completion over an already-connected [ControlConnection]
 * (pairing.md §3). TLS still applies on this connection - the certificate simply isn't
 * pinned yet, since [PinnedTls][com.slipstream.core.control.PinnedTls]'s unpinned-accept
 * path is what got a stranger this far. Only the initiator sends the first `pair.offer`;
 * both sides otherwise run an identical receive loop, dispatching *only*
 * [PairingMessageTypes.OFFER]/[PairingMessageTypes.CONFIRM]/[PairingMessageTypes.CANCEL] -
 * anything else is silently ignored, exactly like [com.slipstream.core.control.JsonLineCodec]'s
 * own skip-unknown-type rule, and never reaches anything resembling a browse/transfer
 * session.
 *
 * [remoteVerifiedFingerprint] and [remoteCertificate] must come from the TLS handshake that
 * produced [connection] - never parsed out of a `pair.offer` payload. [decide] is invoked
 * once the code has been derived and displayed; it returns true to confirm, false to
 * decline, and drives [PairingSession.confirmLocally] / [PairingSession.cancel].
 */
class PairingCoordinator(
    private val identity: DeviceIdentity,
    private val peerStore: PairedPeerStore,
    private val connection: ControlConnection,
    private val remoteVerifiedFingerprint: String,
    private val remoteCertificate: X509Certificate,
    private val isInitiator: Boolean,
    private val decide: (code: String) -> Boolean,
) {
    val session = PairingSession(identity)

    /**
     * Runs the exchange to completion (blocking on [ControlConnection.receive]). Persists
     * the peer via [peerStore] and returns true only once both sides have confirmed; returns
     * false on cancellation by either side or connection loss.
     */
    fun run(): Boolean {
        if (isInitiator) {
            connection.send(offerMessage())
        }

        var offeredBack = false
        while (session.state == PairingState.Idle || session.state == PairingState.AwaitingConfirmation) {
            val msg = connection.receive()
            if (msg == null) {
                session.cancel()
                break
            }

            when (msg.type) {
                PairingMessageTypes.OFFER -> {
                    val offer = decodeOffer(msg) ?: continue
                    session.receiveOffer(offer, remoteVerifiedFingerprint)

                    if (session.state == PairingState.Cancelled) {
                        sendCancel()
                        break
                    }

                    if (!isInitiator && !offeredBack) {
                        connection.send(offerMessage())
                        offeredBack = true
                    }

                    if (decide(session.code!!)) {
                        session.confirmLocally()
                        connection.send(ControlMessage(type = PairingMessageTypes.CONFIRM))
                    } else {
                        session.cancel()
                        sendCancel()
                    }
                }

                PairingMessageTypes.CONFIRM -> session.receiveConfirm()

                PairingMessageTypes.CANCEL -> session.cancel()

                // Restricted handler: anything outside pair.offer/confirm/cancel is ignored,
                // never dispatched (pairing.md §2).
                else -> Unit
            }
        }

        if (session.state == PairingState.Confirmed) {
            val outcome = session.result ?: return false
            peerStore.store(PairedPeer(outcome.deviceId, outcome.fingerprint, remoteCertificate))
            return true
        }
        return false
    }

    private fun sendCancel() {
        try {
            connection.send(ControlMessage(type = PairingMessageTypes.CANCEL))
        } catch (e: Exception) {
            // Best-effort notification; the local state is already Cancelled regardless.
        }
    }

    private fun offerMessage(): ControlMessage {
        val payload = json.encodeToJsonElement(session.localOffer()) as JsonObject
        return ControlMessage(type = PairingMessageTypes.OFFER, payload = payload)
    }

    private fun decodeOffer(msg: ControlMessage): PairingOffer? {
        val payload = msg.payload ?: return null
        return try {
            json.decodeFromJsonElement<PairingOffer>(payload)
        } catch (e: Exception) {
            null
        }
    }
}
