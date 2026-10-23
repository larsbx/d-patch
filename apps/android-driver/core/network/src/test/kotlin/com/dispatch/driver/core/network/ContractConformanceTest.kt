package com.dispatch.driver.core.network

import com.dispatch.driver.core.model.capability.SignedDocument
import com.dispatch.driver.core.model.location.DemandPolicyBody
import com.dispatch.driver.core.model.location.LocationSample
import com.dispatch.driver.core.model.status.CurrentStatus
import com.dispatch.driver.core.model.status.StatusDeclaration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Section 19.1 makes OpenAPI canonical at the transport boundary. These DTOs
 * are written by hand, so this test is what keeps them generated-in-effect:
 * every field the client sends or reads must exist in the server's contract —
 * biz-kit's `apps/dispatch/contracts/openapi.json`, copied here as
 * `contracts/biz-kit-dispatch.openapi.json` by `scripts/sync-dispatch-contract.sh`
 * — under the same name, a field the contract requires of a request must be one
 * the client always sends, and a field the client requires of a response must
 * be one the contract requires the server to send. `mix openapi.check` fails
 * the server side of the same drift.
 */
class ContractConformanceTest {

    private val contract: JsonObject = Json.parseToJsonElement(
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "contracts/biz-kit-dispatch.openapi.json") }
            .first { it.isFile }
            .readText(),
    ).jsonObject

    private fun schema(name: String) = contract["components"]!!.jsonObject["schemas"]!!.jsonObject[name]!!.jsonObject

    private fun properties(name: String) = schema(name)["properties"]!!.jsonObject.keys

    private fun required(name: String) = schema(name)["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()

    private fun fields(serializer: KSerializer<*>) =
        serializer.descriptor.let { d -> (0 until d.elementsCount).associate { d.getElementName(it) to !d.isElementOptional(it) } }

    private fun assertNamesExist(serializer: KSerializer<*>, schemaName: String) {
        val unknown = fields(serializer).keys - properties(schemaName)
        assertTrue("$schemaName lacks $unknown", unknown.isEmpty())
    }

    /** The client may rely on a response field only if the server must send it. */
    private fun assertResponse(serializer: KSerializer<*>, schemaName: String) {
        assertNamesExist(serializer, schemaName)
        val relied = fields(serializer).filterValues { it }.keys
        val unpromised = relied - required(schemaName)
        assertTrue("$schemaName does not require $unpromised", unpromised.isEmpty())
    }

    @Test
    fun `the queued declaration is a valid StatusEventRequest`() {
        assertNamesExist(StatusDeclaration.serializer(), "StatusEventRequest")
        val alwaysSent = fields(StatusDeclaration.serializer()).filterValues { it }.keys
        val missing = required("StatusEventRequest") - alwaysSent
        assertTrue("client does not always send $missing", missing.isEmpty())
    }

    @Test
    fun `a device registration is a valid DeviceRegistrationRequest`() {
        assertNamesExist(DeviceRegistrationRequest.serializer(), "DeviceRegistrationRequest")
        val missing = required("DeviceRegistrationRequest") - fields(DeviceRegistrationRequest.serializer()).filterValues { it }.keys
        assertTrue("client does not always send $missing", missing.isEmpty())
    }

    @Test
    fun `responses read only what the contract promises`() {
        assertResponse(StoredBody.serializer(), "StatusEventResponse")
        assertResponse(CurrentStatus.serializer(), "CurrentStatus")
        assertResponse(SignedDocument.serializer(), "CapabilityDocumentResponse")
        assertResponse(AssignmentsBody.serializer(), "RoleAssignmentList")
        assertResponse(RoleAssignmentChoice.serializer(), "RoleAssignmentChoice")
        assertResponse(RegisteredDevice.serializer(), "DeviceRegistration")
        assertNamesExist(ProblemBody.serializer(), "Problem")
    }

    // A sample is built before it is signed, so the type leaves the device
    // fields optional; `uploadSamples` refuses one without them.
    @Test
    fun `a signed sample is a valid LocationSampleItem`() {
        assertNamesExist(LocationSample.serializer(), "LocationSampleItem")
        val missing = required("LocationSampleItem") - fields(LocationSample.serializer()).filterValues { it }.keys
        assertEquals(setOf("device_id", "device_signature"), missing)
    }

    @Test
    fun `location requests send and responses read only what the contract says`() {
        assertNamesExist(LocationConsentRequest.serializer(), "LocationConsentRequest")
        assertEquals(required("LocationConsentRequest"), fields(LocationConsentRequest.serializer()).filterValues { it }.keys)
        assertNamesExist(SampleBatchBody.serializer(), "LocationSampleBatch")
        assertResponse(LocationConsent.serializer(), "LocationConsent")
        assertResponse(SampleResultsBody.serializer(), "LocationSampleResults")
        assertResponse(DemandPolicyBody.serializer(), "LocationDemandPolicy")
        assertResponse(TripsBody.serializer(), "TripList")
        assertResponse(Trip.serializer(), "Trip")

        // The results' items are an inline schema.
        val item = schema("LocationSampleResults")["properties"]!!.jsonObject["results"]!!.jsonObject["items"]!!.jsonObject
        val itemFields = fields(SampleResult.serializer())
        assertTrue(item["properties"]!!.jsonObject.keys.containsAll(itemFields.keys))
        val promised = item["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue(promised.containsAll(itemFields.filterValues { it }.keys))
    }

    @Test
    fun `every route and header the client uses is in the contract`() {
        val paths = contract["paths"]!!.jsonObject
        fun headers(path: String, method: String) =
            paths[path]!!.jsonObject[method]!!.jsonObject["parameters"]?.jsonArray
                ?.map { it.jsonObject["name"]!!.jsonPrimitive.content.lowercase() }.orEmpty().toSet()

        assertTrue(headers("/v1/me/status-events", "post").containsAll(setOf("idempotency-key", "x-role-assignment-id")))
        assertTrue(headers("/v1/role-capabilities", "get").contains("x-role-assignment-id"))
        assertTrue(headers("/v1/me/devices", "post").contains("x-role-assignment-id"))
        assertTrue(paths["/v1/me/role-assignments"]!!.jsonObject.containsKey("get"))
        assertTrue(headers("/v1/me/location-consents", "post").containsAll(setOf("idempotency-key", "x-role-assignment-id")))
        assertTrue(headers("/v1/me/location-consents/{consent_id}/revoke", "post").containsAll(setOf("idempotency-key", "x-role-assignment-id")))
        assertTrue(headers("/v1/me/location-samples:batch", "post").containsAll(setOf("idempotency-key", "x-role-assignment-id")))
        assertTrue(headers("/v1/me/location-demand", "get").contains("x-role-assignment-id"))
        assertTrue(headers("/v1/me/assignments", "get").contains("x-role-assignment-id"))
        assertTrue(paths["/v1/me/location-demand"]!!.jsonObject["get"]!!.jsonObject["responses"]!!.jsonObject["200"]!!
            .jsonObject["content"]!!.jsonObject.containsKey("text/event-stream"))
    }
}
