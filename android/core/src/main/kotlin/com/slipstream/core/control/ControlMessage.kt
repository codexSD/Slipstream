package com.slipstream.core.control

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire shape of a control-channel message (protocol.md §5): one of these, serialized as a
 * single UTF-8 JSON object per line. `id` is present on requests and echoed on the matching
 * response; it is absent on events. `payload` is message-specific and optional.
 */
@Serializable
data class ControlMessage(
    val type: String,
    val id: String? = null,
    val payload: JsonObject? = null,
)
