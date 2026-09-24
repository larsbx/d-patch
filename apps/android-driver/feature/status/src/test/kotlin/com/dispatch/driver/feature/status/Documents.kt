package com.dispatch.driver.feature.status

import com.dispatch.driver.core.model.capability.Capability
import com.dispatch.driver.core.model.capability.CapabilityDocument
import com.dispatch.driver.core.model.capability.RoleSummary
import com.dispatch.driver.core.model.capability.StatusOption

/**
 * Verified documents for the screen's tests. Verification itself is
 * `CapabilityDocumentVerifierTest`'s job; here the document is a given and the
 * question is what the screen does with it.
 */
internal fun document(
    features: Set<String> = setOf("home", "status"),
    roleLabel: String = "Courier",
    options: List<StatusOption> = listOf(
        StatusOption("AT_PICKUP", "At pickup", requiresNote = false),
        StatusOption("DELAYED", "Delayed", requiresNote = true),
    ),
) = CapabilityDocument(
    schemaVersion = 1,
    subject = "subject-1",
    tenantId = "t",
    participantId = "p",
    roleAssignmentId = "ra-1",
    role = RoleSummary("COURIER", roleLabel, 0),
    capabilities = listOf(Capability("status.declare.self", listOf("self_only"))),
    features = features,
    statusOptions = options,
    issuedAtEpochSeconds = 0,
    expiresAtEpochSeconds = 43_200,
)
