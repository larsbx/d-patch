package com.dispatch.driver.core.model.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `data` of a `location-demand` event (biz-kit `LocationDemandPolicy`, ADR-0014). */
class DemandPolicyTest {

    @Test
    fun `the server's two modes`() {
        assertEquals(
            DemandPolicy(DemandPolicy.Mode.REALTIME, sampleIntervalMs = 3_000, uploadIntervalMs = 0),
            DemandPolicy.parse("""{"mode":"realtime","sample_interval_ms":3000,"upload_interval_ms":0}"""),
        )
        assertEquals(
            DemandPolicy.TRICKLE,
            DemandPolicy.parse("""{"mode":"trickle","sample_interval_ms":120000,"upload_interval_ms":600000}"""),
        )
    }

    @Test
    fun `fields the app does not know yet are ignored`() {
        assertEquals(
            DemandPolicy.TRICKLE,
            DemandPolicy.parse("""{"mode":"trickle","sample_interval_ms":120000,"upload_interval_ms":600000,"min_displacement_m":50}"""),
        )
    }

    // A policy the app cannot apply safely is no policy: it keeps the one it has.
    @Test
    fun `what cannot be applied is refused`() {
        for (
            data in listOf(
                """{"mode":"warp","sample_interval_ms":1,"upload_interval_ms":0}""",
                """{"mode":"realtime","sample_interval_ms":0,"upload_interval_ms":0}""",
                """{"mode":"realtime","sample_interval_ms":3000,"upload_interval_ms":-1}""",
                """{"mode":"realtime","sample_interval_ms":3000}""",
                """{"mode":"realtime","sample_interval_ms":"3000","upload_interval_ms":0}""",
                """[]""",
                "not json",
            )
        ) assertNull(data, DemandPolicy.parse(data))
    }

    @Test
    fun `with no demand stream the app trickles`() {
        assertEquals(DemandPolicy.Mode.TRICKLE, DemandPolicy.TRICKLE.mode)
        assertEquals(120_000L, DemandPolicy.TRICKLE.sampleIntervalMs)
        assertEquals(600_000L, DemandPolicy.TRICKLE.uploadIntervalMs)
    }
}
