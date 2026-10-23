package com.dispatch.driver.core.model.location

import com.dispatch.driver.core.model.device.DeviceKey
import com.dispatch.driver.core.model.device.DeviceStatements
import com.dispatch.driver.core.model.device.SigningDevice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64
import kotlin.math.abs
import kotlin.random.Random

/**
 * A sample as biz-kit verifies it (ADR-0013): fixed-point fields, signed over
 * `DispatchLocationSampleV1`, byte for byte the shared `location_sample` vector.
 */
class LocationSampleTest {

    private val vectors: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("device-signing-vectors.json")).readText(),
    ).jsonObject
    private val vector = vectors.getValue("location_sample").jsonObject
    private val fields = vector.getValue("fields").jsonObject

    private fun bytes(value: String) = Base64.getUrlDecoder().decode(value)
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private val device = SigningDevice(fields.text("device_id"), DeviceKey.fromSeed(bytes(vectors.text("seed"))))

    // The fix the vector's integers came from, as a phone reports it.
    private val fix = LocationFix(
        lat = 51.5074123,
        lng = -0.1278456,
        accuracyM = 12.40,
        speedMps = 12.50,
        bearingDeg = 270.50,
        provider = "fused",
        isMock = false,
        occurredAt = Instant.parse("2026-10-22T10:00:00Z"),
    )

    private val sample = LocationSample.of(
        fix,
        sampleId = fields.text("sample_id"),
        sharing = Sharing(assignmentId = fields.text("assignment_id"), consentGrantId = fields.text("consent_grant_id")),
        deviceSequence = fields.getValue("device_sequence").jsonPrimitive.long,
    )

    @Test
    fun `the statement is biz-kit's, from the sample's own fields`() {
        assertArrayEquals(bytes(vector.text("statement")), DeviceStatements.sample(fields))
    }

    @Test
    fun `a fix becomes the vector's fixed-point fields, signed as biz-kit verifies`() {
        val body = Json.parseToJsonElement(sample.signedBy(device).toCanonicalJson()).jsonObject

        assertEquals(fields, JsonObject(body - "device_signature"))
        assertEquals(vector.text("signature"), body.text("device_signature"))
    }

    @Test
    fun `queued once, sent unchanged`() {
        val signed = sample.signedBy(device)
        assertEquals(signed, LocationSample.fromCanonicalJson(signed.toCanonicalJson()))
    }

    @Test
    fun `optional measurements are left out, not sent as null`() {
        val bare = LocationSample.of(fix.copy(speedMps = null, bearingDeg = null), "s", Sharing("a", "c"), 1)
        val body = Json.parseToJsonElement(bare.signedBy(device).toCanonicalJson()).jsonObject

        assertTrue(body.keys.none { it == "speed_cmps" || it == "bearing_cdeg" })
    }

    // The proof obligation of ADR-0013's "about 1 cm": rounding to the nearest
    // unit is off by at most half a unit, for every coordinate on Earth.
    @Test
    fun `fixed point is within half a unit of the fix, everywhere`() {
        val random = Random(2026_10_23)
        repeat(100_000) {
            val lat = random.nextDouble(-90.0, 90.0)
            val lng = random.nextDouble(-180.0, 180.0)
            val accuracy = random.nextDouble(0.0, 5_000.0)
            val s = LocationSample.of(fix.copy(lat = lat, lng = lng, accuracyM = accuracy), "s", Sharing("a", "c"), 1)

            assertTrue("$lat", abs(s.latE7 / 1e7 - lat) <= 0.5e-7 + 1e-15)
            assertTrue("$lng", abs(s.lngE7 / 1e7 - lng) <= 0.5e-7 + 1e-15)
            assertTrue("$accuracy", abs(s.accuracyCm / 100.0 - accuracy) <= 0.005 + 1e-12)
        }
    }

    @Test
    fun `the poles and the antimeridian fit their integers`() {
        for ((lat, lng) in listOf(90.0 to 180.0, -90.0 to -180.0)) {
            val s = LocationSample.of(fix.copy(lat = lat, lng = lng), "s", Sharing("a", "c"), 1)
            assertEquals(Math.round(lat * 1e7), s.latE7)
            assertEquals(Math.round(lng * 1e7), s.lngE7)
        }
    }
}
