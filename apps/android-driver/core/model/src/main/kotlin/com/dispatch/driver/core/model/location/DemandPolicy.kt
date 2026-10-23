package com.dispatch.driver.core.model.location

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

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
            fun integer(key: String) = (fields[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            val mode = Mode.entries.single { (fields["mode"] as JsonPrimitive).content == it.wire }
            DemandPolicy(mode, integer("sample_interval_ms")!!, integer("upload_interval_ms")!!)
        }.getOrNull()
    }
}
