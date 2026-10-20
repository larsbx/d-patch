package com.dispatch.driver.core.model.status

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The body of `POST /v1/me/status-events` (Section 24.1), as queued.
 *
 * This is the `canonical_json` of Section 25.2's `pending_events` row: written
 * once at declaration time and sent unchanged on every retry, so a replay is
 * byte-identical and the server's duplicate detection sees the same
 * declaration rather than a re-rendering of it.
 *
 * No participant identifier: Section 24.1 derives it from the token, and a
 * field the client cannot send is stronger than one the server ignores.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StatusDeclaration(
    @SerialName("event_id") val eventId: String,
    val status: String,
    @SerialName("occurred_at") val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val note: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("assignment_id") val assignmentId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_sequence") val deviceSequence: Long,
) {
    fun toCanonicalJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { explicitNulls = false }

        fun fromCanonicalJson(value: String): StatusDeclaration = json.decodeFromString(serializer(), value)

        /** Section 24.1's bound, in Unicode code points rather than UTF-16 units. */
        const val NOTE_MAX_CODE_POINTS = 1_000
    }
}

/** The server's `current_status` (Section 24.1) — the declared status and its provenance. */
@Serializable
data class CurrentStatus(
    val value: String,
    val source: String,
    @SerialName("occurred_at") val occurredAt: String,
)
