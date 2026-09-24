package com.dispatch.driver.core.model.capability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The server capability document of Section 23.2, after verification.
 *
 * Features are enabled from this, never from a role key. [role] is carried
 * for presentation and audit display only: Section 34's Slice 1 exit criterion
 * forbids any feature module branching on it, and `scripts/check-invariants.sh`
 * fails the build if one does.
 *
 * Instances come only from [CapabilityDocumentVerifier]; nothing else in the
 * app should construct one from untrusted bytes.
 */
@Serializable
data class CapabilityDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("sub") val subject: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("participant_id") val participantId: String,
    @SerialName("role_assignment_id") val roleAssignmentId: String,
    val role: RoleSummary,
    val capabilities: List<Capability>,
    val features: Set<String>,
    @SerialName("status_options") val statusOptions: List<StatusOption>,
    @SerialName("iat") val issuedAtEpochSeconds: Long,
    @SerialName("exp") val expiresAtEpochSeconds: Long,
) {
    /** Whether the server offers `feature` to this assignment. */
    fun offers(feature: String): Boolean = feature in features

    /** Whether the document has passed its expiry at [nowEpochSeconds]. */
    fun isStale(nowEpochSeconds: Long): Boolean = nowEpochSeconds >= expiresAtEpochSeconds

    /** The status option for a canonical code, if this assignment may declare it. */
    fun statusOption(value: String): StatusOption? = statusOptions.firstOrNull { it.value == value }

    companion object {
        /** The only schema this client understands; ADR-0009. */
        const val SCHEMA_VERSION = 1
    }
}

/** Presentation facts about the role definition behind the assignment. */
@Serializable
data class RoleSummary(val key: String, val label: String, val version: Int)

/** A granted capability and the conditions Section 23.2 attaches to it. */
@Serializable
data class Capability(val key: String, val constraints: List<String>)

/**
 * One status the participant may declare (Section 5.1).
 *
 * The vocabulary travels in the document so the client holds no second copy
 * of it: labels, order, and the note rule of Section 24.1 are the server's.
 */
@Serializable
data class StatusOption(
    val value: String,
    val label: String,
    @SerialName("requires_note") val requiresNote: Boolean,
)

/** Feature keys the server may publish (`Dispatch.Access.Roles.*`). */
object Features {
    const val STATUS = "status"
}
