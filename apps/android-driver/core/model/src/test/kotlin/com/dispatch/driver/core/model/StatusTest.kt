package com.dispatch.driver.core.model

import org.junit.Assert.*
import org.junit.Test

class StatusTest {
    @Test fun capabilityNotRoleNameEnablesStatus() {
        val context = RoleContext("assignment", setOf("status.declare.self"))
        assertTrue(context.canDeclareStatus(1))
    }

    @Test fun expiredAssignmentCannotDeclare() {
        val context = RoleContext("assignment", setOf("status.declare.self"), 10)
        assertFalse(context.canDeclareStatus(10))
    }

    @Test fun delayRequiresNote() {
        assertNotNull(StatusDraft(ParticipantStatus.DELAYED, " ", null).validationError())
    }
}
