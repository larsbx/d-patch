package com.dispatch.driver.core.model.session

import kotlinx.serialization.json.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class EnrolmentStatementTest {
    @Test fun `the server canonical enrolment vector binds this complete key principal and operator role`() {
        val vector = Json.parseToJsonElement(requireNotNull(javaClass.classLoader?.getResource("native-enrolment-vector.json")).readText()).jsonObject
        val decode = Base64.getUrlDecoder()
        val keyId = vector.getValue("key_id").jsonPrimitive.content
        val public = decode.decode(vector.getValue("public_key").jsonPrimitive.content)
        val principal = vector.getValue("principal").jsonPrimitive.content
        val bytes = decode.decode(vector.getValue("enrolment").jsonPrimitive.content)
        assertTrue(EnrolmentStatement.isOwn(bytes, keyId, public, principal))
        assertFalse(EnrolmentStatement.isOwn(bytes, keyId, public, "another-principal"))
        assertFalse(EnrolmentStatement.isOwn(bytes, keyId, public.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }, principal))
        assertFalse(EnrolmentStatement.isOwn(decode.decode(vector.getValue("command").jsonPrimitive.content), keyId, public, principal))
        assertFalse(EnrolmentStatement.isOwn(decode.decode(vector.getValue("reviewer").jsonPrimitive.content), keyId, public, principal))
    }
}
