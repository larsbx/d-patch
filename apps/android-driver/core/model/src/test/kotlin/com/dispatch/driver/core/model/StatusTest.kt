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

    @Test fun uuidV7CarriesVersionVariantAndUnixMillis() {
        val millis = 1_700_000_000_123L
        val id = UuidV7.generate(millis)

        assertEquals(7, id.version())
        assertEquals(2, id.variant())
        assertEquals(
            millis,
            (id.mostSignificantBits ushr 16) and 0x0000_FFFF_FFFF_FFFFL,
        )
    }
}
