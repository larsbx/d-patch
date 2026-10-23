package com.dispatch.driver.core.model.location

import com.dispatch.driver.core.model.device.DeviceStatements
import com.dispatch.driver.core.model.device.SigningDevice
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.Base64

/** A position as the platform's location provider reports it, in its own units. */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val provider: String,
    val isMock: Boolean,
    val occurredAt: Instant,
)

/** What a sample is shared under: the trip and the consent covering it (Section 25.3). */
data class Sharing(val assignmentId: String, val consentGrantId: String)

/**
 * One item of `POST /v1/me/location-samples:batch` (Section 24.2), as queued.
 *
 * Fixed-point integers (ADR-0013): the canonical form a device signs has no
 * floats, so the fix is converted once, here, at acquisition, and never
 * re-derived from a stored float. Like `StatusDeclaration`, it is written once
 * and sent unchanged however late.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LocationSample(
    @SerialName("sample_id") val sampleId: String,
    @SerialName("assignment_id") val assignmentId: String,
    @SerialName("consent_grant_id") val consentGrantId: String,
    @SerialName("lat_e7") val latE7: Long,
    @SerialName("lng_e7") val lngE7: Long,
    @SerialName("accuracy_cm") val accuracyCm: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("speed_cmps") val speedCmps: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("bearing_cdeg") val bearingCdeg: Long? = null,
    val provider: String,
    @SerialName("is_mock") val isMock: Boolean,
    @SerialName("occurred_at") val occurredAt: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_sequence") val deviceSequence: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("device_signature") val deviceSignature: String? = null,
) {
    fun toCanonicalJson(): String = json.encodeToString(serializer(), this)

    /** This sample as [device] sends it: naming the device, signed over every other field. */
    fun signedBy(device: SigningDevice): LocationSample {
        val unsigned = copy(deviceId = device.id, deviceSignature = null)
        val fields = json.parseToJsonElement(unsigned.toCanonicalJson()).jsonObject
        val signature = device.sign(DeviceStatements.sample(fields))
        return unsigned.copy(deviceSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
    }

    companion object {
        private val json = Json { explicitNulls = false }

        fun fromCanonicalJson(value: String): LocationSample = json.decodeFromString(serializer(), value)

        /** [fix] in fixed point: degrees × 10⁷, centimetres, cm/s and centidegrees, each rounded. */
        fun of(fix: LocationFix, sampleId: String, sharing: Sharing, deviceSequence: Long) = LocationSample(
            sampleId = sampleId,
            assignmentId = sharing.assignmentId,
            consentGrantId = sharing.consentGrantId,
            latE7 = fixed(fix.lat, 1e7),
            lngE7 = fixed(fix.lng, 1e7),
            accuracyCm = fixed(fix.accuracyM, 100.0),
            speedCmps = fix.speedMps?.let { fixed(it, 100.0) },
            bearingCdeg = fix.bearingDeg?.let { fixed(it, 100.0) },
            provider = fix.provider,
            isMock = fix.isMock,
            occurredAt = fix.occurredAt.toString(),
            deviceSequence = deviceSequence,
        )

        private fun fixed(value: Double, scale: Double): Long = Math.round(value * scale)
    }
}
