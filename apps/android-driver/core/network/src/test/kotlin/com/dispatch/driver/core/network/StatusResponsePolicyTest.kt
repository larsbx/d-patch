package com.dispatch.driver.core.network

import org.junit.Assert.*
import org.junit.Test

class StatusResponsePolicyTest {
    @Test fun serverFailureRetries() =
        assertTrue(StatusResponsePolicy.classify(503, null) is UploadOutcome.Retry)

    @Test fun forbiddenIsPermanentAndActionable() {
        val outcome = StatusResponsePolicy.classify(403, null) as UploadOutcome.Rejected
        assertEquals("ROLE_ASSIGNMENT_FORBIDDEN", outcome.code)
        assertTrue(outcome.correctiveAction.contains("role assignment"))
    }

    @Test fun inProgressConflictRetries() =
        assertTrue(StatusResponsePolicy.classify(409,
            Problem(code = "IDEMPOTENCY_KEY_IN_PROGRESS")) is UploadOutcome.Retry)
}
