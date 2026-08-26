package com.slipstream.core.pairing

import com.slipstream.core.control.PinnedTls
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.Fingerprint
import com.slipstream.core.net.LanGuard
import com.slipstream.core.net.NetworkBinder
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real [PairingProbe]: opens an *unpinned* TLS connection and reports the certificate
 * fingerprint the handshake actually proved.
 *
 * Unpinned because there is nothing to pin against until pairing completes - the six-digit
 * code, not a stored fingerprint, is what establishes trust here (pairing.md §5). The probe
 * therefore only ever produces a *candidate*: an address plus a verified fingerprint. It does
 * not exchange a `pair.offer`, so the peer's device id and name are unknown at this point and
 * are left empty; [PairingCoordinator] learns them, and every trust decision is still made
 * there, behind mutual confirmation.
 *
 * [LanGuard] applies to every probe ([PinnedTls.connect] enforces it), and an unreachable
 * host is a null, never an exception - during a 254-way sweep unreachable is the normal answer.
 */
class TlsPairingProbe(
    private val identity: DeviceIdentity,
    private val binder: NetworkBinder = NetworkBinder.NONE,
    private val timeout: Duration = 3.seconds,
) : PairingProbe {

    override suspend fun probe(endpoint: InetSocketAddress): PairingCandidate? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeout) {
                val address = endpoint.address ?: return@withTimeoutOrNull null
                if (!LanGuard.isLocal(address)) return@withTimeoutOrNull null

                try {
                    PinnedTls.connect(endpoint, identity, binder) { true }.use { socket ->
                        val certificate = socket.session.peerCertificates.firstOrNull()
                            as? X509Certificate ?: return@use null
                        val fingerprint = Fingerprint.of(certificate)

                        // Never discover ourselves - a sweep can and does reach our own
                        // addresses.
                        if (fingerprint == identity.fingerprint) return@use null

                        PairingCandidate(
                            deviceId = "",
                            name = "",
                            fingerprint = fingerprint,
                            endpoint = endpoint,
                        )
                    }
                } catch (e: Exception) {
                    // Unreachable, refused, or the peer closed its window. Not an error here.
                    null
                }
            }
        }
}
