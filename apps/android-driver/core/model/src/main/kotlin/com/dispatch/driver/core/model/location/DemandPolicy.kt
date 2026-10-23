package com.dispatch.driver.core.model.location

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How often to sample and upload until told otherwise (ADR-0014): the `data`
 * of a `location-demand` event. `uploadIntervalMs == 0` uploads each sample
 * as soon as it is acquired.
 */
data class DemandPolicy(val mode: Mode, val sampleIntervalMs: Long, val uploadIntervalMs: Long) {

    init {
        require(sampleIntervalMs > 0) { "A sample interval is positive" }
        require(uploadIntervalMs >= 0) { "An upload interval is not negative" }
    }

    enum class Mode(val wire: String) { REALTIME("realtime"), TRICKLE("trickle") }

    companion object {
        /** No one is watching, or the app cannot tell: the server's trickle. */
        val TRICKLE = DemandPolicy(Mode.TRICKLE, sampleIntervalMs = 120_000, uploadIntervalMs = 600_000)

        /** The policy in [data], or null when it cannot be applied as sent; unknown fields are ignored. */
        fun parse(data: String): DemandPolicy? = runCatching {
            val fields = Json.parseToJsonElement(data) as JsonObject
            // The decoder would read a quoted interval as a number; the contract says number.
            require(INTERVALS.none { (fields[it] as? JsonPrimitive)?.isString == true })
            val body = json.decodeFromJsonElement(DemandPolicyBody.serializer(), fields)
            DemandPolicy(Mode.entries.single { it.wire == body.mode }, body.sampleIntervalMs, body.uploadIntervalMs)
        }.getOrNull()

        private val json = Json { ignoreUnknownKeys = true }
        private val INTERVALS = listOf("sample_interval_ms", "upload_interval_ms")
    }
}

/** The wire shape of a `location-demand` event's `data` (biz-kit `LocationDemandPolicy`). */
@Serializable
data class DemandPolicyBody(
    val mode: String,
    @SerialName("sample_interval_ms") val sampleIntervalMs: Long,
    @SerialName("upload_interval_ms") val uploadIntervalMs: Long,
)
