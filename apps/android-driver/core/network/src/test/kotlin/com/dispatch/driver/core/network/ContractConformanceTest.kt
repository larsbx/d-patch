package com.dispatch.driver.core.network

import com.dispatch.driver.core.model.status.CurrentStatus
import com.dispatch.driver.core.model.status.StatusDeclaration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Section 19.1 makes OpenAPI canonical at the transport boundary. These DTOs
 * are written by hand, so this test is what keeps them generated-in-effect:
 * every field the client sends or reads must exist in `contracts/openapi.json`
 * under the same name, a field the contract requires of a request must be one
 * the client always sends, and a field the client requires of a response must
 * be one the contract requires the server to send. `mix openapi.check` fails
 * the server side of the same drift.
 */
class ContractConformanceTest {

    private val contract: JsonObject = Json.parseToJsonElement(
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "contracts/openapi.json") }
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
    fun `responses read only what the contract promises`() {
        assertResponse(StoredBody.serializer(), "StatusEventResponse")
        assertResponse(CurrentStatus.serializer(), "CurrentStatus")
        assertResponse(DocumentBody.serializer(), "CapabilityDocumentResponse")
        assertResponse(AssignmentsBody.serializer(), "RoleAssignmentList")
        assertResponse(RoleAssignmentChoice.serializer(), "RoleAssignmentChoice")
        assertNamesExist(ProblemBody.serializer(), "Problem")
    }

    @Test
    fun `every route and header the client uses is in the contract`() {
        val paths = contract["paths"]!!.jsonObject
        fun headers(path: String, method: String) =
            paths[path]!!.jsonObject[method]!!.jsonObject["parameters"]?.jsonArray
                ?.map { it.jsonObject["name"]!!.jsonPrimitive.content.lowercase() }.orEmpty().toSet()

        assertTrue(headers("/v1/me/status-events", "post").containsAll(setOf("idempotency-key", "x-role-assignment-id")))
        assertTrue(headers("/v1/role-capabilities", "get").contains("x-role-assignment-id"))
        assertTrue(paths["/v1/me/role-assignments"]!!.jsonObject.containsKey("get"))
    }
}
