package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamPorts
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A peer announcement or query message sent over UDP multicast or as a unicast reply.
 *
 * Wire format is strictly ordered by field name: v, deviceId, name, fingerprint, control, kind.
 * This is normative per protocol.md §6 and must match the announcement vectors exactly.
 */
@Serializable
data class PeerAnnouncement(
    @SerialName("v")
    val v: Int,
    @SerialName("deviceId")
    val deviceId: String,
    @SerialName("name")
    val name: String,
    @SerialName("fingerprint")
    val fingerprint: String,
    @SerialName("control")
    val control: Int,
    @SerialName("kind")
    val kind: String,
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        /**
         * Attempts to parse an announcement from JSON. Returns null if:
         * - JSON parsing fails
         * - v doesn't match the current protocol version
         * - deviceId or fingerprint is blank
         * - control is out of the 1-65535 range
         * - any required field is missing
         *
         * This is used to parse untrusted network data, so all errors are
         * recovered gracefully (return null, don't throw).
         */
        fun tryParse(json: String): PeerAnnouncement? {
            return try {
                val announcement = Json.decodeFromString<PeerAnnouncement>(json)

                // Validate protocol version
                if (announcement.v != SlipstreamPorts.PROTOCOL_VERSION) {
                    return null
                }

                // Validate deviceId is not blank
                if (announcement.deviceId.isBlank()) {
                    return null
                }

                // Validate fingerprint is not blank
                if (announcement.fingerprint.isBlank()) {
                    return null
                }

                // Validate control port is in range [1, 65535]
                if (announcement.control < 1 || announcement.control > 65535) {
                    return null
                }

                announcement
            } catch (e: SerializationException) {
                // JSON parsing failed or a field had the wrong type
                null
            } catch (e: IllegalArgumentException) {
                // kotlinx.serialization also throws this for malformed JSON
                null
            }
        }
    }
}
