package com.dispatch.driver.core.model.session

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor

/** The existing browser's isOwnEnrolment guard, before any signature is made.
 * A closed statement rejects command fields (including type) and other roles.
 * The exact received bytes are signed only after their own-key binding passes.
 */
@OptIn(ExperimentalSerializationApi::class)
object EnrolmentStatement {
    fun isOwn(bytes: ByteArray, keyId: String, publicKey: ByteArray, principal: String): Boolean {
        val value = runCatching { Cbor.decodeFromByteArray(Statement.serializer(), bytes) }.getOrNull() ?: return false
        return value.schema == "CoopDeviceEnrolmentV1" && value.role == "operator" &&
            value.keyId == keyId && value.publicKey.contentEquals(publicKey) &&
            value.principal == principal && value.nonce.isNotBlank()
    }

    @Serializable
    private data class Statement(
        val schema: String,
        @SerialName("principal_id") val principal: String,
        @SerialName("chapter_id") val chapter: String,
        @SerialName("tenant_id") val tenant: String,
        @SerialName("project_id") val project: String,
        @SerialName("key_id") val keyId: String,
        @ByteString @SerialName("pubkey") val publicKey: ByteArray,
        val label: String,
        val role: String,
        val nonce: String,
    )
}
