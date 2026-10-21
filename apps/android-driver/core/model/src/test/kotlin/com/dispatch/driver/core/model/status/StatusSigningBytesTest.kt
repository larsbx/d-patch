package com.dispatch.driver.core.model.status

import kotlinx.serialization.json.*
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class StatusSigningBytesTest {
    @Test fun `canonical bytes agree with the actual Elixir Rust encoder including Unicode and integer boundaries`() {
        val raw = requireNotNull(javaClass.classLoader?.getResource("native-status-vector.json")).readText()
        val vector = Json.parseToJsonElement(raw).jsonObject
        val declaration = StatusDeclaration.fromCanonicalJson(vector.getValue("payload").toString())
        val bytes = StatusSigningBytes.encode(declaration, vector.getValue("role_assignment_id").jsonPrimitive.content)
        val decode = Base64.getUrlDecoder()
        assertArrayEquals(decode.decode(vector.getValue("signing_bytes").jsonPrimitive.content), bytes)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(decode.decode(vector.getValue("public_key").jsonPrimitive.content), 0))
        verifier.update(bytes, 0, bytes.size)
        assertTrue(verifier.verifySignature(decode.decode(vector.getValue("signature").jsonPrimitive.content)))
        assertFalse(bytes.contentEquals(StatusSigningBytes.encode(declaration, "another-role")))
        assertFalse(bytes.contentEquals(StatusSigningBytes.encode(declaration.copy(subject = "another-person"), vector.getValue("role_assignment_id").jsonPrimitive.content)))
    }
}
